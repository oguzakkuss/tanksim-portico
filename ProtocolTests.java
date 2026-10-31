import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Sunucu/acik federasyon gerektirmeyen protokol sozlesmesi kontrolleri. */
public final class ProtocolTests {
    public static void main(String[] args) throws Exception {
        SimulationMessages.Ready ready = new SimulationMessages.Ready("run", "Prediction");
        byte[] expected = ByteBuffer.allocate(34)
            .putInt(30).putInt(0x5453494d).putInt(1).put((byte) 1)
            .putInt(3).put("run".getBytes(StandardCharsets.UTF_8))
            .putInt(10).put("Prediction".getBytes(StandardCharsets.UTF_8)).array();
        require(Arrays.equals(expected, MessageCodec.encodePayload(ready)),
            "Ready bagimsiz binary sozlesme");
        roundTrip("FederateReady", ready);
        roundTrip("FederateReady", new SimulationMessages.Ready("calisma-\u00e7-\u96ea", "Display"));
        for (String action : List.of("STEER", "MOVE_STOP", "FIRE", "STOP")) {
            roundTrip("ControlCommand", new SimulationMessages.Control("run", Long.MAX_VALUE,
                action, -0.5, 0.75));
        }
        SimulationMessages.Shot shot = new SimulationMessages.Shot("run", 42,
            new EntityId(65535, 32768, 1), 2.0, 12, 13, -6, 8,
            14, 15, 30, 40, 100, 80, 2, 0.2);
        roundTrip("ShotSnapshot", shot);

        List<SimulationMessages.Segment> input = new ArrayList<>();
        input.add(new SimulationMessages.Segment(2, 3, 12, 13, -6, 8));
        input.add(new SimulationMessages.Segment(3, 4, 6, 21, 6, 8));
        SimulationMessages.Trajectories paths = new SimulationMessages.Trajectories(shot, input, 4);
        input.clear();
        require(paths.targetSegments.size() == 2, "Yorunge listesi kopyasi degistirilemez");
        try {
            paths.targetSegments.clear();
            throw new AssertionError("Liste degistirildi");
        } catch (UnsupportedOperationException expectedFailure) { }
        roundTrip("PredictedTrajectories", paths);
        for (String status : List.of("HIT", "NO_HIT", "ERROR")) {
            roundTrip("CollisionResult", new SimulationMessages.Result("run", 42, status,
                3.141592653589793, "temas \u00e7\u00f6z\u00fcm\u00fc"));
        }

        byte[] valid = MessageCodec.encodePayload(ready);
        reject(() -> MessageCodec.decodePayload("FederateReady", null));
        reject(() -> MessageCodec.decodePayload("Unknown", valid));
        reject(() -> MessageCodec.decodePayload("ControlCommand", valid));
        byte[] badLength = valid.clone();
        ByteBuffer.wrap(badLength).putInt(1);
        reject(() -> MessageCodec.decodePayload("FederateReady", badLength));
        byte[] badVersion = valid.clone();
        ByteBuffer.wrap(badVersion).putInt(8, 2);
        reject(() -> MessageCodec.decodePayload("FederateReady", badVersion));
        byte[] badStringLength = valid.clone();
        ByteBuffer.wrap(badStringLength).putInt(13, Integer.MAX_VALUE);
        reject(() -> MessageCodec.decodePayload("FederateReady", badStringLength));
        byte[] malformedUtf8 = valid.clone();
        malformedUtf8[17] = (byte) 0xc0;
        reject(() -> MessageCodec.decodePayload("FederateReady", malformedUtf8));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            if (length >= 4) ByteBuffer.wrap(truncated).putInt(length - 4);
            reject(() -> MessageCodec.decodePayload("FederateReady", truncated));
        }
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        ByteBuffer.wrap(trailing).putInt(trailing.length - 4);
        reject(() -> MessageCodec.decodePayload("FederateReady", trailing));
        reject(() -> MessageCodec.encodePayload(new SimulationMessages.Control("run", 1,
            "STEER", Double.NaN, 0)));
        reject(() -> MessageCodec.encodePayload(new SimulationMessages.Control("run", 1,
            "UNKNOWN", 0, 0)));
        reject(() -> MessageCodec.encodePayload(new SimulationMessages.Result("run", 1,
            "HIT", Double.POSITIVE_INFINITY, "")));
        reject(() -> MessageCodec.encodePayload(new SimulationMessages.Result("run", 1,
            "ERROR", 0, "x".repeat(4097))));
        byte[] oversized = new byte[1024 * 1024 + 5];
        reject(() -> MessageCodec.decodePayload("FederateReady", oversized));
        System.out.println("ProtocolTests PASS: 5 mesaj tipi, binary cerceve, UTF-8, immutable liste, bozuk veri sinirlari.");
    }

    private static void roundTrip(String name, Object source) throws Exception {
        byte[] bytes = MessageCodec.encodePayload(source);
        Object decoded = MessageCodec.decodePayload("HLAinteractionRoot." + name, bytes);
        equal(source, decoded);
    }

    private static void equal(Object a, Object b) throws Exception {
        require(a.getClass() == b.getClass(), "Tip korundu");
        if (a instanceof String || a instanceof Number || a instanceof EntityId) {
            require(a.equals(b), "Alan degeri korundu: " + a + " / " + b);
        } else if (a instanceof List) {
            List<?> left = (List<?>) a, right = (List<?>) b;
            require(left.size() == right.size(), "Liste boyutu korundu");
            for (int i = 0; i < left.size(); i++) equal(left.get(i), right.get(i));
        } else {
            for (Field field : a.getClass().getFields()) equal(field.get(a), field.get(b));
        }
    }

    private static void reject(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Bozuk veri kabul edildi");
        } catch (IllegalArgumentException expected) { }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
