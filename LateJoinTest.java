import hla.rti1516e.*;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.exceptions.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Çalışan federasyona beşinci gözlemci katılır: FOM yenilemesi ve tam snapshot sınanır. */
public final class LateJoinTest {
    public static void main(String[] args) throws Exception {
        String federation = "TankLate_" + UUID.randomUUID().toString().replace("-", "");
        Path folder = Files.createDirectories(Paths.get("build", "integration", federation));
        Map<String, Process> children = new LinkedHashMap<>();
        Map<String, Path> logs = new LinkedHashMap<>();
        Observer observer = new Observer();
        try {
            String[] roles = {"Tank", "Prediction", "Collision", "Display"};
            String[] classes = {"TankFederate", "PredictionFederate", "CollisionDetectionFederate", "DisplayFederate"};
            for (int i = 0; i < roles.length; i++) {
                Path log = folder.resolve(roles[i] + ".log");
                Process child = launch(classes[i], federation, log);
                children.put(roles[i], child); logs.put(roles[i], log);
                waitText(child, log, "RTI_JOINED", 30);
            }
            waitText(children.get("Tank"), logs.get("Tank"), "all_four_ready=true", 15);
            observer.open(federation);
            observer.verify();
            long deadline = afterSeconds(10);
            for (Map.Entry<String, Process> entry : children.entrySet()) {
                if (!entry.getValue().waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                    throw new AssertionError(entry.getKey() + " kapanmadı; logs=" + folder);
                require(entry.getValue().exitValue() == 0, entry.getKey() + " exit != 0; logs=" + folder);
            }
            String tank = read(logs.get("Tank"));
            require(tank.contains("BULLET_EXIT id=1"), "Mermi gerçek çıkışında silinmedi");
            require(tank.contains("STOP_REQUESTED"), "STOP işlenmedi");
            // Tank coordinator olduğundan kendi FOM referansı korunabilir; uzaktaki üçü yenilenir.
            for (String role : Arrays.asList("Prediction", "Collision", "Display"))
                require(read(logs.get(role)).contains("FOM_DECLARATIONS_REFRESHED"), role + " FOM yenilenmedi");
            System.out.println("LateJoinTest PASS: full_state=true stationary_player=true NO_HIT=true logs="
                    + folder.toAbsolutePath());
        } finally {
            try { observer.close(); }
            finally {
                for (Process child : children.values()) if (child.isAlive()) child.destroyForcibly();
                for (Process child : children.values()) child.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    private static Process launch(String mainClass, String federation, Path log) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Djava.net.preferIPv4Stack=true", "-Dfile.encoding=UTF-8", "-Dportico.mom=disabled",
                "-Dtanksim.federation=" + federation, "-Dtanksim.startupTimeoutSeconds=40",
                "-Dtanksim.autoFire=false", "-Dtanksim.autoStopSeconds=0",
                "-cp", System.getProperty("java.class.path"), mainClass));
        if ("DisplayFederate".equals(mainClass)) command.add("--headless");
        return new ProcessBuilder(command).directory(new File("."))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void waitText(Process process, Path log, String text, int seconds) throws Exception {
        long deadline = afterSeconds(seconds);
        while (!read(log).contains(text)) {
            if (!process.isAlive() || System.nanoTime() > deadline)
                throw new AssertionError("Başlangıç başarısız: " + log + "\n" + read(log));
            Thread.sleep(50);
        }
    }
    private static long afterSeconds(int seconds) { return System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds); }
    private static String read(Path path) throws Exception { return Files.readString(path, StandardCharsets.UTF_8); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    @FunctionalInterface private interface Event { void apply() throws Exception; }
    private static final class View {
        EntityId id;
        SpatialState spatial;
    }

    /** Raw RTI kullanır; dördüncü Display adını veya uygulama Ready protokolünü tekrar kullanmaz. */
    private static final class Observer extends NullFederateAmbassador implements AutoCloseable {
        private RTIambassador rti;
        private EncoderFactory decoder;
        private SimulationStateCodec states;
        private MessageCodec messages;
        private ObjectClassHandle tankClass;
        private AttributeHandle idAttribute, spatialAttribute;
        private AttributeHandleSet tankAttributes;
        private ObjectInstanceHandle stateObject;
        private final Queue<Event> events = new ArrayDeque<>();
        private final Queue<ObjectInstanceHandle> requests = new ArrayDeque<>();
        private final Map<ObjectInstanceHandle, View> tanks = new HashMap<>();
        private SimulationStateData state = SimulationStateData.empty();
        private SimulationMessages.Result result;
        private String federation;
        private boolean connected, joined, fired, stopped, removed;

        void open(String federation) throws Exception {
            this.federation = federation;
            System.setProperty("portico.mom", "disabled");
            RtiFactory factory = RtiFactoryFactory.getRtiFactory();
            rti = factory.getRtiAmbassador(); decoder = factory.getEncoderFactory();
            rti.connect(this, CallbackModel.HLA_EVOKED); connected = true;
            rti.joinFederationExecution("LateObserver_" + UUID.randomUUID(), "Observer", federation);
            joined = true;
            states = new SimulationStateCodec(rti);
            messages = new MessageCodec(rti, "Display");
            tankClass = rti.getObjectClassHandle("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.GroundVehicle");
            idAttribute = rti.getAttributeHandle(tankClass, "EntityIdentifier");
            spatialAttribute = rti.getAttributeHandle(tankClass, "Spatial");
            tankAttributes = rti.getAttributeHandleSetFactory().create();
            tankAttributes.add(idAttribute); tankAttributes.add(spatialAttribute);
            rti.subscribeObjectClassAttributes(states.objectClass, states.attributes(rti));
            rti.subscribeObjectClassAttributes(tankClass, tankAttributes);
            System.out.println("Late observer joined after RUNNING: " + federation);
        }

        void verify() throws Exception {
            long deadline = afterSeconds(30);
            while (!removed && state.phase != SimulationStateData.Phase.STOPPING) {
                require(System.nanoTime() < deadline, "Geç gözlemci zaman aşımı: stateComplete="
                        + state.isComplete() + " tanks=" + tanks.size() + " fired=" + fired + " result=" + result);
                rti.evokeMultipleCallbacks(0.001, 0.01);
                Event event;
                while ((event = events.poll()) != null) event.apply();
                ObjectInstanceHandle object;
                while ((object = requests.poll()) != null)
                    rti.requestAttributeValueUpdate(object,
                            object.equals(stateObject) ? states.attributes(rti) : tankAttributes, new byte[0]);
                require(state.phase != SimulationStateData.Phase.ERROR, "Tank ERROR yayımladı");
                if (!fired && state.isComplete() && completeTanks()
                        && state.phase == SimulationStateData.Phase.RUNNING) {
                    View player = tank(state.playerEntityId);
                    require(player.spatial.vx == 0 && player.spatial.vy == 0 && player.spatial.vz == 0,
                            "Başlangıçtaki oyuncu sabit olmalı");
                    require(state.simTime > 0, "Gözlemci çalışan simülasyona geç katılmalı");
                    send(new SimulationMessages.Control(state.runId, 1, "FIRE", 0, 0)); fired = true;
                    System.out.println("FULL_SNAPSHOT_OK stationaryPlayer=" + player.id + " simTime=" + state.simTime);
                }
                if (result != null) {
                    require(state.runId.equals(result.runId) && result.shotId == 1, "Yanlış atış sonucu");
                    require("NO_HIT".equals(result.status), "NO_HIT bekleniyordu: " + result.status);
                    if (!stopped && state.simTime >= result.eventTime + 0.1) {
                        send(new SimulationMessages.Control(state.runId, 2, "STOP", 0, 0)); stopped = true;
                    }
                }
            }
            require(fired && stopped && result != null, "Atış/sonuç/STOP zinciri tamamlanmadı");
        }
        private View tank(EntityId id) {
            for (View view : tanks.values()) if (id.equals(view.id)) return view;
            return null;
        }
        private boolean completeTanks() {
            View target = tank(state.targetEntityId), player = tank(state.playerEntityId);
            return target != null && target.spatial != null && player != null && player.spatial != null
                    && !state.targetEntityId.equals(state.playerEntityId);
        }
        private void send(Object message) throws Exception {
            MessageCodec.Encoded encoded = messages.encode(message);
            rti.sendInteraction(encoded.interaction, encoded.parameters, new byte[0]);
        }
        @Override public void discoverObjectInstance(ObjectInstanceHandle object, ObjectClassHandle type, String name) {
            events.add(() -> {
                if (type.equals(states.objectClass)) stateObject = object;
                else if (type.equals(tankClass)) tanks.putIfAbsent(object, new View());
                else return;
                requests.add(object);
            });
        }
        @Override public void discoverObjectInstance(ObjectInstanceHandle object, ObjectClassHandle type,
                String name, FederateHandle producer) { discoverObjectInstance(object, type, name); }
        @Override public void reflectAttributeValues(ObjectInstanceHandle object, AttributeHandleValueMap values,
                byte[] tag, OrderType order, TransportationTypeHandle transport, SupplementalReflectInfo info) {
            Map<AttributeHandle, byte[]> copy = new HashMap<>();
            values.forEach((key, value) -> copy.put(key, value.clone()));
            events.add(() -> {
                if (object.equals(stateObject)) state = state.merge(states.decode(decoder, copy));
                else {
                    View view = tanks.get(object);
                    if (view == null) return;
                    if (copy.containsKey(idAttribute)) view.id = EntityIdCodec.decode(decoder, copy.get(idAttribute));
                    if (copy.containsKey(spatialAttribute)) view.spatial = SpatialDecoder.decode(copy.get(spatialAttribute));
                }
            });
        }
        @Override public void receiveInteraction(InteractionClassHandle kind, ParameterHandleValueMap values,
                byte[] tag, OrderType order, TransportationTypeHandle transport, SupplementalReceiveInfo info) {
            Map<ParameterHandle, byte[]> copy = new HashMap<>();
            values.forEach((key, value) -> copy.put(key, value.clone()));
            events.add(() -> result = (SimulationMessages.Result) messages.decode(kind, copy));
        }
        @Override public void removeObjectInstance(ObjectInstanceHandle object, byte[] tag,
                OrderType order, SupplementalRemoveInfo info) {
            events.add(() -> { if (object.equals(stateObject)) removed = true; else tanks.remove(object); });
        }
        @Override public void connectionLost(String reason) {
            events.add(() -> { throw new AssertionError("Observer bağlantısı kesildi: " + reason); });
        }
        @Override public void close() throws Exception {
            try {
                if (joined) {
                    rti.resignFederationExecution(ResignAction.NO_ACTION); joined = false;
                    try { rti.destroyFederationExecution(federation); }
                    catch (FederatesCurrentlyJoined | FederationExecutionDoesNotExist expected) { }
                }
            } finally {
                if (connected) { rti.disconnect(); connected = false; }
            }
        }
    }
}
