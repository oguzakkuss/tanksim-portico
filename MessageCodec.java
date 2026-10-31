import hla.rti1516e.InteractionClassHandle;
import hla.rti1516e.ParameterHandle;
import hla.rti1516e.ParameterHandleValueMap;
import hla.rti1516e.ParameterHandleValueMapFactory;
import hla.rti1516e.RTIambassador;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tek Payload parametresinin tipi HLAopaqueData'dir: 4 byte BE octet sayisi + octetler.
 * Ic uygulama protokolu (tum sayilar big-endian): magic int32=0x5453494d,
 * version int32=1, message-kind uint8 (1=Ready, 2=Control, 3=Shot, 4=Trajectories,
 * 5=Result). String: int32 UTF-8 byte sayisi + byte'lar; long=int64; double=float64.
 * Ready: runId, role. Control: runId, commandId, action, x, y.
 * Shot: runId, shotId, bulletId(3 uint16), t0,tx,ty,tvx,tvy,bx,by,bvx,bvy,
 * width,height,targetRadius,bulletRadius.
 * Trajectories: Shot govdesi, endTime, int32 parca sayisi, her parca icin
 * start,end,x,y,vx,vy. Result: runId,shotId,status,eventTime,detail.
 * FOM'un HLAunicodeString tipine ozel UTF-8 byte'lar koyulmaz; bu acikca ozel
 * HLAopaqueData uygulama sozlesmesidir.
 */
public final class MessageCodec {
    private static final int MAGIC = 0x5453494d;
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_STRING_BYTES = 4096;
    private static final int MAX_SEGMENTS = 20_000;
    private static final String[] NAMES = {
        "FederateReady", "ControlCommand", "ShotSnapshot",
        "PredictedTrajectories", "CollisionResult"
    };

    public static final class Encoded {
        public final InteractionClassHandle interaction;
        public final ParameterHandleValueMap parameters;
        Encoded(InteractionClassHandle interaction, ParameterHandleValueMap parameters) {
            this.interaction = interaction;
            this.parameters = parameters;
        }
    }

    private static final class Binding {
        final String name;
        final InteractionClassHandle kind;
        final ParameterHandle payload;
        final boolean published;
        Binding(String name, InteractionClassHandle kind, ParameterHandle payload,
                boolean published) {
            this.name = name;
            this.kind = kind;
            this.payload = payload;
            this.published = published;
        }
    }

    private final Map<String, Binding> byName;
    private final Map<InteractionClassHandle, Binding> byHandle;
    private final ParameterHandleValueMapFactory mapFactory;

    // Bu constructor ve encode yalniz RTI thread'inde kullanilir.
    public MessageCodec(RTIambassador rti, String role) throws Exception {
        if (!List.of("Tank", "Display", "Prediction", "Collision").contains(role)) {
            throw new IllegalArgumentException("Bilinmeyen federe rolu: " + role);
        }
        Map<String, Binding> names = new HashMap<>();
        Map<InteractionClassHandle, Binding> handles = new HashMap<>();
        for (String name : NAMES) {
            InteractionClassHandle kind = rti.getInteractionClassHandle("HLAinteractionRoot." + name);
            ParameterHandle payload = rti.getParameterHandle(kind, "Payload");
            boolean publish = publishes(role, name);
            Binding binding = new Binding(name, kind, payload, publish);
            names.put(name, binding);
            handles.put(kind, binding);
            if (publish) rti.publishInteractionClass(kind);
            if (subscribes(role, name)) rti.subscribeInteractionClass(kind);
        }
        byName = Map.copyOf(names);
        byHandle = Map.copyOf(handles);
        mapFactory = rti.getParameterHandleValueMapFactory();
    }

    public Encoded encode(Object message) {
        Binding binding = byName.get(nameOf(message));
        if (!binding.published) {
            throw new IllegalArgumentException("Bu federe mesaji yayinlamiyor: " + binding.name);
        }
        ParameterHandleValueMap parameters = mapFactory.create(1);
        parameters.put(binding.payload, encodePayload(message));
        return new Encoded(binding.kind, parameters);
    }

    public boolean knows(InteractionClassHandle kind) {
        return byHandle.containsKey(kind);
    }

    // Worker icin saf decode: RTI cagrisi yok, handle tablolari degismez.
    public Object decode(InteractionClassHandle kind, Map<ParameterHandle, byte[]> values) {
        Binding binding = byHandle.get(kind);
        if (binding == null) throw new IllegalArgumentException("Bilinmeyen interaction.");
        byte[] payload = values.get(binding.payload);
        if (payload == null) throw new IllegalArgumentException("Payload parametresi eksik.");
        return decodePayload(binding.name, payload);
    }

    private static boolean publishes(String role, String name) {
        switch (role) {
            case "Tank": return name.equals("ShotSnapshot");
            case "Display": return name.equals("FederateReady") || name.equals("ControlCommand");
            case "Prediction": return name.equals("FederateReady")
                || name.equals("PredictedTrajectories") || name.equals("CollisionResult");
            case "Collision": return name.equals("FederateReady") || name.equals("CollisionResult");
            default: return false;
        }
    }

    private static boolean subscribes(String role, String name) {
        switch (role) {
            case "Tank": return name.equals("FederateReady") || name.equals("ControlCommand")
                || name.equals("CollisionResult");
            case "Display": return name.equals("CollisionResult");
            case "Prediction": return name.equals("ShotSnapshot");
            case "Collision": return name.equals("PredictedTrajectories");
            default: return false;
        }
    }

    private static String nameOf(Object message) {
        if (message instanceof SimulationMessages.Ready) return NAMES[0];
        if (message instanceof SimulationMessages.Control) return NAMES[1];
        if (message instanceof SimulationMessages.Shot) return NAMES[2];
        if (message instanceof SimulationMessages.Trajectories) return NAMES[3];
        if (message instanceof SimulationMessages.Result) return NAMES[4];
        throw new IllegalArgumentException("Desteklenmeyen mesaj tipi.");
    }

    public static byte[] encodePayload(Object message) {
        validate(message);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeByte(List.of(NAMES).indexOf(nameOf(message)) + 1);
            if (message instanceof SimulationMessages.Ready) {
                SimulationMessages.Ready m = (SimulationMessages.Ready) message;
                writeString(out, m.runId);
                writeString(out, m.role);
            } else if (message instanceof SimulationMessages.Control) {
                SimulationMessages.Control m = (SimulationMessages.Control) message;
                writeString(out, m.runId);
                out.writeLong(m.commandId);
                writeString(out, m.action);
                out.writeDouble(m.x);
                out.writeDouble(m.y);
            } else if (message instanceof SimulationMessages.Shot) {
                writeShot(out, (SimulationMessages.Shot) message);
            } else if (message instanceof SimulationMessages.Trajectories) {
                SimulationMessages.Trajectories m = (SimulationMessages.Trajectories) message;
                writeShot(out, m.shot);
                out.writeDouble(m.endTime);
                out.writeInt(m.targetSegments.size());
                for (SimulationMessages.Segment s : m.targetSegments) {
                    out.writeDouble(s.start);
                    out.writeDouble(s.end);
                    out.writeDouble(s.x);
                    out.writeDouble(s.y);
                    out.writeDouble(s.vx);
                    out.writeDouble(s.vy);
                }
            } else {
                SimulationMessages.Result m = (SimulationMessages.Result) message;
                writeString(out, m.runId);
                out.writeLong(m.shotId);
                writeString(out, m.status);
                out.writeDouble(m.eventTime);
                writeString(out, m.detail);
            }
            out.flush();
            byte[] body = bytes.toByteArray();
            if (body.length > MAX_BYTES) throw new IllegalArgumentException("Mesaj fazla buyuk.");
            return ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
        } catch (IOException e) {
            throw new IllegalStateException("Bellekte kodlama hatasi.", e);
        }
    }

    public static Object decodePayload(String interactionName, byte[] bytes) {
        String name = interactionName.startsWith("HLAinteractionRoot.")
            ? interactionName.substring("HLAinteractionRoot.".length()) : interactionName;
        int expectedType = List.of(NAMES).indexOf(name) + 1;
        if (expectedType == 0) throw new IllegalArgumentException("Bilinmeyen interaction: " + name);
        if (bytes == null || bytes.length < 13 || bytes.length > MAX_BYTES + 4
                || ByteBuffer.wrap(bytes).getInt() != bytes.length - 4) {
            throw new IllegalArgumentException("HLAopaqueData uzunlugu gecersiz.");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 4, bytes.length - 4));
            if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readUnsignedByte() != expectedType) {
                throw new IllegalArgumentException("Protokol surumu veya mesaj tipi uyusmuyor.");
            }
            Object result;
            switch (expectedType) {
                case 1:
                    result = new SimulationMessages.Ready(readString(in), readString(in));
                    break;
                case 2:
                    result = new SimulationMessages.Control(readString(in), in.readLong(),
                        readString(in), in.readDouble(), in.readDouble());
                    break;
                case 3:
                    result = readShot(in);
                    break;
                case 4:
                    SimulationMessages.Shot shot = readShot(in);
                    double endTime = in.readDouble();
                    int count = in.readInt();
                    if (count < 0 || count > MAX_SEGMENTS || count > in.available() / 48) {
                        throw new IllegalArgumentException("Yorunge parca sayisi gecersiz.");
                    }
                    List<SimulationMessages.Segment> segments = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        segments.add(new SimulationMessages.Segment(in.readDouble(), in.readDouble(),
                            in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()));
                    }
                    result = new SimulationMessages.Trajectories(shot, segments, endTime);
                    break;
                case 5:
                    result = new SimulationMessages.Result(readString(in), in.readLong(),
                        readString(in), in.readDouble(), readString(in));
                    break;
                default: throw new IllegalArgumentException("Mesaj tipi gecersiz.");
            }
            if (in.available() != 0) throw new IllegalArgumentException("Mesaj sonunda fazla byte var.");
            validate(result);
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException("Eksik veya bozuk mesaj.", e);
        }
    }

    private static void writeShot(DataOutputStream out, SimulationMessages.Shot m) throws IOException {
        writeString(out, m.runId);
        out.writeLong(m.shotId);
        out.writeShort(m.bulletId.siteId);
        out.writeShort(m.bulletId.applicationId);
        out.writeShort(m.bulletId.entityNumber);
        for (double value : new double[] {m.t0, m.tx, m.ty, m.tvx, m.tvy, m.bx, m.by,
                m.bvx, m.bvy, m.width, m.height, m.targetRadius, m.bulletRadius}) {
            out.writeDouble(value);
        }
    }

    private static SimulationMessages.Shot readShot(DataInputStream in) throws IOException {
        String runId = readString(in);
        long shotId = in.readLong();
        EntityId id = new EntityId(in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedShort());
        return new SimulationMessages.Shot(runId, shotId, id, in.readDouble(),
            in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
            in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
            in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("Metin fazla uzun.");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_STRING_BYTES || count > in.available()) {
            throw new IllegalArgumentException("Metin uzunlugu gecersiz.");
        }
        byte[] bytes = new byte[count];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("UTF-8 metin gecersiz.", e);
        }
    }

    private static void validate(Object message) {
        if (message instanceof SimulationMessages.Ready) {
            SimulationMessages.Ready m = (SimulationMessages.Ready) message;
            run(m.runId);
            allowed(m.role, "Display", "Prediction", "Collision");
        } else if (message instanceof SimulationMessages.Control) {
            SimulationMessages.Control m = (SimulationMessages.Control) message;
            run(m.runId);
            id(m.commandId);
            allowed(m.action, "STEER", "MOVE_STOP", "FIRE", "STOP");
            finite(m.x, m.y);
        } else if (message instanceof SimulationMessages.Shot) {
            SimulationMessages.Shot m = (SimulationMessages.Shot) message;
            run(m.runId);
            id(m.shotId);
            finite(m.t0, m.tx, m.ty, m.tvx, m.tvy, m.bx, m.by, m.bvx, m.bvy,
                m.width, m.height, m.targetRadius, m.bulletRadius);
            if (m.t0 < 0 || m.width <= 0 || m.height <= 0
                    || m.targetRadius < 0 || m.bulletRadius < 0) {
                throw new IllegalArgumentException("Atis geometrisi veya zamani gecersiz.");
            }
        } else if (message instanceof SimulationMessages.Trajectories) {
            SimulationMessages.Trajectories m = (SimulationMessages.Trajectories) message;
            validate(m.shot);
            finite(m.endTime);
            if (m.endTime < m.shot.t0 || m.targetSegments.size() > MAX_SEGMENTS) {
                throw new IllegalArgumentException("Yorunge suresi veya boyutu gecersiz.");
            }
            for (SimulationMessages.Segment s : m.targetSegments) {
                finite(s.start, s.end, s.x, s.y, s.vx, s.vy);
                if (s.start < m.shot.t0 || s.end < s.start || s.end > m.endTime) {
                    throw new IllegalArgumentException("Yorunge parcasi zamani gecersiz.");
                }
            }
        } else if (message instanceof SimulationMessages.Result) {
            SimulationMessages.Result m = (SimulationMessages.Result) message;
            run(m.runId);
            id(m.shotId);
            allowed(m.status, "HIT", "NO_HIT", "ERROR");
            finite(m.eventTime);
            if (m.eventTime < 0) throw new IllegalArgumentException("Sonuc zamani negatif.");
        } else {
            throw new IllegalArgumentException("Desteklenmeyen mesaj tipi.");
        }
    }

    private static void run(String value) {
        if (value.isEmpty() || value.length() > 128) throw new IllegalArgumentException("runId gecersiz.");
    }
    private static void id(long value) {
        if (value < 0) throw new IllegalArgumentException("Kimlik negatif olamaz.");
    }
    private static void finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Sayisal alan sonlu olmali.");
        }
    }
    private static void allowed(String value, String... choices) {
        if (!List.of(choices).contains(value)) throw new IllegalArgumentException("Gecersiz deger: " + value);
    }
}
