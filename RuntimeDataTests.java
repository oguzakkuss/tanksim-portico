import hla.rti1516e.AttributeHandle;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.RTIambassador;
import hla.rti1516e.RtiFactoryFactory;
import hla.rti1516e.encoding.DecoderException;
import hla.rti1516e.encoding.EncoderFactory;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Veri sinirlarini test eder; RTI baglantisi, pencere veya acik federasyon gerekmez. */
public final class RuntimeDataTests {
    public static void main(String[] args) throws Exception {
        EncoderFactory f = RtiFactoryFactory.getRtiFactory().getEncoderFactory();
        identity(f);
        partialState(f);
        spatial(f);
        System.out.println("RuntimeDataTests PASS: kimlik, kismi durum, Unicode ve 60-byte Spatial.");
    }

    private static void identity(EncoderFactory f) throws Exception {
        EntityId id = new EntityId(1, 1, 2);
        require(Arrays.equals(EntityIdCodec.encode(f, id), new byte[] {0, 1, 0, 1, 0, 2}),
            "RPR kimlik byte sirasi");
        for (int site : new int[] {0, 32767, 32768, 65535}) {
            for (int application : new int[] {0, 32767, 32768, 65535}) {
                for (int number : new int[] {0, 32767, 32768, 65535}) {
                    EntityId source = new EntityId(site, application, number);
                    EntityId decoded = EntityIdCodec.decode(f, EntityIdCodec.encode(f, source));
                    require(source.equals(decoded) && source.hashCode() == decoded.hashCode(),
                        "Unsigned16 kimlik: " + source);
                }
            }
        }
        reject(() -> new EntityId(-1, 1, 1));
        reject(() -> new EntityId(1, 65536, 1));
        reject(() -> new EntityId(1, 1, -1));
        for (int length : new int[] {0, 5, 7}) {
            try {
                EntityIdCodec.decode(f, new byte[length]);
                throw new AssertionError("Yanlis kimlik uzunlugu kabul edildi");
            } catch (DecoderException expected) { }
        }
    }

    private static void partialState(EncoderFactory f) throws Exception {
        Map<String, AttributeHandle> handles = new HashMap<>();
        ObjectClassHandle objectClass = handle(ObjectClassHandle.class, "SimulationState");
        // Constructor handle'lari cozer. Bundan sonraki decode RTI'ye dokunursa test hata verir.
        RTIambassador rti = (RTIambassador) Proxy.newProxyInstance(
            RTIambassador.class.getClassLoader(), new Class<?>[] {RTIambassador.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getObjectClassHandle")) return objectClass;
                if (method.getName().equals("getAttributeHandle")) {
                    String name = (String) args[1];
                    return handles.computeIfAbsent(name, key -> handle(AttributeHandle.class, key));
                }
                throw new AssertionError("Beklenmeyen RTI cagrisi: " + method.getName());
            });
        SimulationStateCodec codec = new SimulationStateCodec(rti);
        require(handles.size() == 8 && handles.containsKey("TargetEntityId")
            && handles.containsKey("PlayerEntityId"), "Sekiz alanin handle sozlesmesi");

        EntityId target = new EntityId(1, 1, 1), player = new EntityId(1, 1, 2);
        Map<AttributeHandle, byte[]> identityOnly = new HashMap<>();
        identityOnly.put(codec.targetEntityId, EntityIdCodec.encode(f, target));
        identityOnly.put(codec.playerEntityId, EntityIdCodec.encode(f, player));
        SimulationStateData ids = codec.decode(f, identityOnly);
        require(ids.runId == null && ids.simTime == null && ids.phase == null,
            "Eksik alanlar sifir degil null");
        require(!ids.isComplete() && target.equals(ids.targetEntityId)
            && player.equals(ids.playerEntityId), "Kismi kimlik decode");

        String runId = "ornek-\u00e7-\u96ea-\ud83d\ude00";
        byte[] encodedRun = SimulationStateCodec.encodeUnicodeString(runId);
        require(ByteBuffer.wrap(encodedRun).getInt() == runId.length()
            && encodedRun.length == 4 + 2 * runId.length(), "HLAunicodeString UTF-16 cercevesi");
        Map<AttributeHandle, byte[]> otherFields = new HashMap<>();
        otherFields.put(codec.runId, encodedRun);
        otherFields.put(codec.simTime, f.createHLAfloat64BE(9.0).toByteArray());
        otherFields.put(codec.phase, f.createHLAinteger32BE(1).toByteArray());
        otherFields.put(codec.mapWidth, f.createHLAfloat64BE(100.0).toByteArray());
        otherFields.put(codec.mapHeight, f.createHLAfloat64BE(80.0).toByteArray());
        otherFields.put(codec.stepSeconds, f.createHLAfloat64BE(0.05).toByteArray());
        SimulationStateData rest = codec.decode(f, otherFields);
        SimulationStateData combined = ids.merge(rest);
        SimulationStateData reverse = rest.merge(ids);
        require(combined.isComplete() && reverse.isComplete(), "Her gelis sirasinda tam durum");
        require(combined.runId.equals(runId) && combined.targetEntityId.equals(target)
            && reverse.playerEntityId.equals(player), "Kimlik/Unicode kaybolmadi");
        require(ids.runId == null && rest.targetEntityId == null, "Kaynak nesneler degismedi");

        Map<AttributeHandle, byte[]> zeroTime = Map.of(codec.simTime,
            f.createHLAfloat64BE(0.0).toByteArray());
        SimulationStateData next = combined.merge(codec.decode(f, zeroTime));
        require(next.simTime == 0.0 && combined.simTime == 9.0, "Sifir zaman gercek guncelleme");
        require(next.runId.equals(runId) && next.targetEntityId.equals(target)
            && next.playerEntityId.equals(player) && next.mapWidth == 100.0
            && next.mapHeight == 80.0 && next.stepSeconds == 0.05
            && next.phase == SimulationStateData.Phase.RUNNING, "Time-only patch digerlerini korudu");
        require(!SimulationStateData.empty().isComplete(), "Bos durum eksik");
        reject(() -> SimulationStateData.Phase.fromCode(99));
        try {
            SimulationStateCodec.decodeUnicodeString(new byte[] {0, 0, 0, 2, 0, 65});
            throw new AssertionError("Bozuk Unicode kabul edildi");
        } catch (DecoderException expected) { }
    }

    private static void spatial(EncoderFactory f) {
        byte[] bytes = SpatialEncoder.encodeFpw(f, 6378137.0, 20.125, -31.25,
            1.5f, -2.25f, 3.75f, 0.25f, -0.5f, 1.0f);
        require(bytes.length == 60 && bytes[0] == 2, "SpatialFPW 60 byte discriminator");
        ByteBuffer independent = ByteBuffer.wrap(bytes);
        require(independent.getDouble(8) == 6378137.0
            && independent.getDouble(16) == 20.125
            && independent.getFloat(36) == 0.25f
            && independent.getFloat(48) == 1.5f, "Bagimsiz FOM offsetleri");
        SpatialState state = SpatialDecoder.decode(bytes);
        require(state.x == 6378137.0 && state.y == 20.125 && state.z == -31.25,
            "Konum decode");
        require(state.vx == 1.5f && state.vy == -2.25f && state.vz == 3.75f,
            "Hiz decode");
        require(state.psi == 0.25f && state.theta == -0.5f && state.phi == 1.0f
            && !state.frozen, "Yonelim ve frozen decode");
        byte[] frozen = bytes.clone();
        frozen[32] = 1;
        require(SpatialDecoder.decode(frozen).frozen, "Frozen offset");
        reject(() -> SpatialDecoder.decode(new byte[59]));
        byte[] wrongAlgorithm = bytes.clone();
        wrongAlgorithm[0] = 3;
        reject(() -> SpatialDecoder.decode(wrongAlgorithm));
        byte[] nan = bytes.clone();
        ByteBuffer.wrap(nan).putDouble(16, Double.NaN);
        reject(() -> SpatialDecoder.decode(nan));
        SpatialState parked = SpatialMapper.fromLocal(20, 20, 0, 0, 1, 0);
        require(parked.vx == 0 && parked.vy == 0 && parked.vz == 0
            && Float.isFinite(parked.psi) && Float.isFinite(parked.theta)
            && Float.isFinite(parked.phi), "Duran tankin yonu sonlu");
    }

    @SuppressWarnings("unchecked")
    private static <T> T handle(Class<T> type, String name) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return name;
                    default: throw new AssertionError("Handle encode gerekmez: " + method.getName());
                }
            });
    }

    private static void reject(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Gecersiz veri kabul edildi");
        } catch (IllegalArgumentException expected) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
