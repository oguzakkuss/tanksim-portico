import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Gerçek Portico ile ayrı JVM'leri çalıştırır. Sadece kendi çocuk süreçlerini kapatır. */
public final class IntegrationTests {
    public static void main(String[] args) throws Exception {
        List<String> cases = args.length == 0
                ? Arrays.asList("hit", "miss", "multi", "delayed", "timeout", "stop_waiting") : Arrays.asList(args);
        for (String scenario : cases) runScenario(scenario);
        System.out.println("IntegrationTests PASS: " + cases);
    }

    private static void runScenario(String scenario) throws Exception {
        String federation = "TankTest_" + UUID.randomUUID().toString().replace("-", "");
        Path folder = Files.createDirectories(Paths.get("build", "integration", federation));
        List<Process> children = new ArrayList<>();
        Map<String, Path> logs = new LinkedHashMap<>();
        Map<String, Process> processes = new LinkedHashMap<>();
        try {
            List<String> properties = new ArrayList<>(Arrays.asList(
                    "-Dtanksim.federation=" + federation,
                    "-Dtanksim.startupTimeoutSeconds=25",
                    "-Dtanksim.autoFire=true", "-Dtanksim.autoStopSeconds=2.2"));
            if ("hit".equals(scenario)) properties.addAll(Arrays.asList(
                    "-Dtanksim.target.x=40", "-Dtanksim.target.y=20",
                    "-Dtanksim.target.dirX=1", "-Dtanksim.target.dirY=0"));
            if ("timeout".equals(scenario)) properties.add("-Dtanksim.predictionTimeoutSeconds=0.4");
            Process tank = launch("TankFederate", "Tank", folder, properties, children, logs, processes);
            waitText(tank, logs.get("Tank"), "RTI_JOINED", 30);
            Process prediction = launch("PredictionFederate", "Prediction", folder, properties, children, logs, processes);
            waitText(prediction, logs.get("Prediction"), "RTI_JOINED", 25);
            boolean customCollision = Arrays.asList("delayed", "timeout", "stop_waiting").contains(scenario);
            Process collision = launch(customCollision ? "IntegrationTests$CollisionPeer" : "CollisionDetectionFederate",
                    "Collision", folder, plus(properties, "-Dtanksim.testScenario=" + scenario),
                    children, logs, processes);
            waitText(collision, logs.get("Collision"), "RTI_JOINED", 25);
            // Display geç katılır: Tank'ın önceden yayımladığı tam state/kimlikler
            // requestAttributeValueUpdate ile yeniden edinilebilmelidir.
            Thread.sleep(500);
            launch("stop_waiting".equals(scenario) ? "IntegrationTests$StopPeer"
                    : "multi".equals(scenario) ? "IntegrationTests$MotionPeer" : "DisplayFederate",
                    "Display", folder, properties, children, logs, processes);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            for (Process child : children) {
                long remaining = Math.max(1, deadline - System.nanoTime());
                if (!child.waitFor(remaining, TimeUnit.NANOSECONDS))
                    throw new AssertionError("Süre aşıldı: " + scenario + " logs=" + folder);
            }
            String tankLog = read(logs.get("Tank"));
            require(tankLog.contains("all_four_ready=true"), "Dört federe hazır olmadı", folder);
            require(tankLog.contains("SHOT id=1"), "Atış yok", folder);
            require(tankLog.contains("WAITING_FOR_PREDICTION"), "Bekleme durumu yok", folder);
            if ("timeout".equals(scenario)) {
                require(tankLog.contains("yanıt süresi aşıldı"), "Timeout ERROR eksik", folder);
                require(tank.exitValue() != 0, "Timeout başarı gibi raporlandı", folder);
                require(!tankLog.contains("HIT_APPLIED"), "Timeout'ta sahte hit", folder);
            } else {
                for (Map.Entry<String, Process> entry : processes.entrySet())
                    require(entry.getValue().exitValue() == 0, entry.getKey() + " exit != 0", folder);
                if ("hit".equals(scenario)) {
                    double expected = number(tankLog, "status=HIT eventTime=([0-9.]+)");
                    double received = number(tankLog, "receivedAt=([0-9.]+)");
                    double applied = number(tankLog, "HIT_APPLIED id=1 simTime=([0-9.]+)");
                    double distance = number(tankLog, "separation=([0-9.]+)");
                    require(expected > received, "Hit erken uygulanmış", folder);
                    require(Math.abs(expected - applied) < 1e-8, "Hit yanlış zamanda", folder);
                    require(Math.abs(distance - 2.2) < 1e-6, "Temas mesafesi yanlış", folder);
                } else if ("stop_waiting".equals(scenario)) {
                    require(tankLog.contains("STOP_REQUESTED"), "Beklemede STOP uygulanmadı", folder);
                    double t0 = number(tankLog, "SHOT id=1 t0=([0-9.]+)");
                    double stopped = number(tankLog, "FINISHED phase=STOPPING simTime=([0-9.]+)");
                    require(Math.abs(t0 - stopped) < 1e-8, "Beklemede saat ilerledi", folder);
                } else {
                    require(tankLog.contains("status=NO_HIT"), "NO_HIT eksik", folder);
                    require(tankLog.contains("BULLET_EXIT id=1"), "Mermi temizlenmedi", folder);
                    require(!tankLog.contains("HIT_APPLIED"), "Yanlış hit", folder);
                    if ("multi".equals(scenario)) {
                        require(count(tankLog, "Tank SHOT id=") == 3, "Tekrarlanan komut ek mermi üretti", folder);
                        require(count(tankLog, "BULLET_EXIT id=") == 3, "Üç mermi temizlenmedi", folder);
                        require(read(logs.get("Display")).contains("MOTION_AND_IDENTITIES_OK"),
                                "Hareket/kimlik gözlemi eksik", folder);
                    }
                    if ("delayed".equals(scenario)) {
                        require(count(tankLog, "RESULT_IGNORED") >= 3, "Eski/tekrar sonuç filtrelenmedi", folder);
                        double t0 = number(tankLog, "SHOT id=1 t0=([0-9.]+)");
                        double received = number(tankLog, "receivedAt=([0-9.]+)");
                        require(Math.abs(t0 - received) < 1e-8, "Hesap beklerken saat ilerledi", folder);
                    }
                }
            }
            System.out.println("PASS " + scenario + " logs=" + folder.toAbsolutePath());
        } finally {
            for (Process child : children) if (child.isAlive()) child.destroyForcibly();
            for (Process child : children) child.waitFor(3, TimeUnit.SECONDS);
        }
    }

    private static Process launch(String className, String role, Path folder, List<String> properties,
            List<Process> children, Map<String, Path> logs, Map<String, Process> processes) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString());
        command.add("-Djava.net.preferIPv4Stack=true"); command.add("-Dfile.encoding=UTF-8");
        command.addAll(properties);
        command.add("-cp"); command.add(System.getProperty("java.class.path"));
        command.add(className);
        if (className.equals("DisplayFederate")) command.add("--headless");
        Path log = folder.resolve(role + ".log");
        Process child = new ProcessBuilder(command).directory(new File("."))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        children.add(child); logs.put(role, log); processes.put(role, child);
        return child;
    }
    private static List<String> plus(List<String> values, String extra) {
        List<String> copy = new ArrayList<>(values); copy.add(extra); return copy;
    }
    private static void waitText(Process process, Path log, String text, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!read(log).contains(text)) {
            if (!process.isAlive() || System.nanoTime() > deadline)
                throw new AssertionError("Başlangıç başarısız: " + log + "\n" + read(log));
            Thread.sleep(50);
        }
    }
    private static String read(Path path) throws Exception { return Files.readString(path, StandardCharsets.UTF_8); }
    private static int count(String text, String token) { return text.split(Pattern.quote(token), -1).length - 1; }
    private static double number(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new AssertionError("Log alanı bulunamadı: " + regex + "\n" + text);
        return Double.parseDouble(m.group(1));
    }
    private static void require(boolean condition, String message, Path folder) {
        if (!condition) throw new AssertionError(message + " logs=" + folder.toAbsolutePath());
    }

    /** Gecikme/yanlış kimlik/timeout enjekte eden test federe; ürün akışında kullanılmaz. */
    public static final class CollisionPeer {
        public static void main(String[] args) throws Exception {
            String scenario = System.getProperty("tanksim.testScenario");
            try (FederateRuntime runtime = new FederateRuntime("Collision", false)) {
                runtime.start();
                SimulationStateData state = SimulationStateData.empty();
                SimulationMessages.Result result = null;
                long nextReady = 0, sendAt = Long.MAX_VALUE, duplicateAt = Long.MAX_VALUE;
                boolean stop = false;
                while (!stop) {
                    runtime.checkFailure();
                    FederateRuntime.Event event;
                    while ((event = runtime.poll()) != null) {
                        if (event.kind == FederateRuntime.Kind.STATE) {
                            state = state.merge(event.state);
                            stop = state.phase == SimulationStateData.Phase.STOPPING
                                    || state.phase == SimulationStateData.Phase.ERROR;
                        } else if (event.kind == FederateRuntime.Kind.REMOVED && "STATE".equals(event.objectType)) stop = true;
                        else if (event.message instanceof SimulationMessages.Trajectories) {
                            SimulationMessages.Trajectories paths = (SimulationMessages.Trajectories) event.message;
                            result = TrajectoryMath.collide(paths);
                            if ("delayed".equals(scenario)) {
                                runtime.send(new SimulationMessages.Result("old-run", result.shotId,
                                        result.status, result.eventTime, "old run"));
                                runtime.send(new SimulationMessages.Result(result.runId, result.shotId + 99,
                                        result.status, result.eventTime, "wrong shot"));
                                sendAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                            }
                        }
                    }
                    long now = System.nanoTime();
                    if (state.isComplete() && state.phase == SimulationStateData.Phase.STARTING && now >= nextReady) {
                        runtime.send(new SimulationMessages.Ready(state.runId, "Collision"));
                        nextReady = now + 500_000_000;
                    }
                    if (now >= sendAt) {
                        runtime.send(result); sendAt = Long.MAX_VALUE; duplicateAt = now + 300_000_000;
                    }
                    if (now >= duplicateAt) { runtime.send(result); duplicateAt = Long.MAX_VALUE; }
                    Thread.sleep(5);
                }
            }
        }
    }

    /** Saat duruyorken STOP'un yine işlenmesini sınar. */
    public static final class StopPeer {
        public static void main(String[] args) throws Exception {
            try (FederateRuntime runtime = new FederateRuntime("Display", false)) {
                runtime.start();
                SimulationStateData state = SimulationStateData.empty();
                long nextReady = 0, sequence = 0;
                boolean fired = false, sentStop = false, done = false;
                while (!done) {
                    runtime.checkFailure();
                    FederateRuntime.Event event;
                    while ((event = runtime.poll()) != null) if (event.kind == FederateRuntime.Kind.STATE) {
                        state = state.merge(event.state);
                        done = state.phase == SimulationStateData.Phase.STOPPING || state.phase == SimulationStateData.Phase.ERROR;
                    }
                    long now = System.nanoTime();
                    if (state.isComplete() && state.phase == SimulationStateData.Phase.STARTING && now >= nextReady) {
                        runtime.send(new SimulationMessages.Ready(state.runId, "Display")); nextReady = now + 500_000_000;
                    }
                    if (state.phase == SimulationStateData.Phase.RUNNING && !fired) {
                        runtime.send(new SimulationMessages.Control(state.runId, ++sequence, "FIRE", 0, 0)); fired = true;
                    }
                    if (state.phase == SimulationStateData.Phase.WAITING_FOR_PREDICTION && !sentStop) {
                        runtime.send(new SimulationMessages.Control(state.runId, ++sequence, "STOP", 0, 0)); sentStop = true;
                    }
                    Thread.sleep(5);
                }
            }
        }
    }

    /** Kontrol komutlarını gerçek ağdan gönderip yayımlanan fizik ve kimlikleri gözler. */
    public static final class MotionPeer {
        public static void main(String[] args) throws Exception {
            try (FederateRuntime runtime = new FederateRuntime("Display", false)) {
                runtime.start();
                SimulationStateData state = SimulationStateData.empty();
                Map<String, EntityId> ids = new HashMap<>();
                Map<String, SpatialState> positions = new HashMap<>();
                Set<EntityId> bulletIds = new HashSet<>();
                long nextReady = 0, sequence = 0;
                int stage = 0, fired = 0, results = 0;
                boolean done = false, stopped = false, sawTarget = false;
                while (!done) {
                    runtime.checkFailure();
                    FederateRuntime.Event event;
                    while ((event = runtime.poll()) != null) {
                        if (event.kind == FederateRuntime.Kind.STATE) {
                            state = state.merge(event.state);
                            done = state.phase == SimulationStateData.Phase.STOPPING
                                    || state.phase == SimulationStateData.Phase.ERROR;
                        } else if (event.kind == FederateRuntime.Kind.ENTITY) {
                            if (event.entityId != null) ids.put(event.objectKey, event.entityId);
                            if (event.spatial != null) positions.put(event.objectKey, event.spatial);
                            EntityId id = ids.get(event.objectKey);
                            SpatialState spatial = positions.get(event.objectKey);
                            if (TankFederate.TARGET_ID.equals(id)) sawTarget = true;
                            if ("BULLET".equals(event.objectType) && id != null && spatial != null) {
                                bulletIds.add(id);
                                if (Math.abs(spatial.vy - 30) > 1e-6 || Math.abs(spatial.vz - 40) > 1e-6)
                                    throw new AssertionError("Duran tankın yönü veya mermi dünya hızı yanlış");
                            }
                        } else if (event.message instanceof SimulationMessages.Result) {
                            SimulationMessages.Result result = (SimulationMessages.Result) event.message;
                            if (!"NO_HIT".equals(result.status)) throw new AssertionError("Beklenmedik sonuç");
                            results++;
                        }
                    }
                    long now = System.nanoTime();
                    if (state.isComplete() && state.phase == SimulationStateData.Phase.STARTING && now >= nextReady) {
                        runtime.send(new SimulationMessages.Ready(state.runId, "Display")); nextReady = now + 500_000_000;
                    }
                    SpatialState player = null;
                    for (Map.Entry<String, EntityId> entry : ids.entrySet())
                        if (TankFederate.PLAYER_ID.equals(entry.getValue())) player = positions.get(entry.getKey());
                    if (state.phase == SimulationStateData.Phase.RUNNING && player != null) {
                        if (stage == 0) {
                            if (Math.hypot(player.vy, player.vz) != 0) throw new AssertionError("Oyuncu başta durmalı");
                            runtime.send(new SimulationMessages.Control(state.runId, ++sequence, "STEER", 3, 4));
                            stage = 1;
                        } else if (stage == 1 && Math.abs(player.vy - 3) < 1e-6 && Math.abs(player.vz - 4) < 1e-6) {
                            runtime.send(new SimulationMessages.Control(state.runId, ++sequence, "MOVE_STOP", 0, 0));
                            stage = 2;
                        } else if (stage == 2 && Math.hypot(player.vy, player.vz) == 0) {
                            stage = 3;
                        }
                        if (stage == 3 && results == fired && fired < 3) {
                            SimulationMessages.Control fire = new SimulationMessages.Control(state.runId, ++sequence, "FIRE", 0, 0);
                            runtime.send(fire);
                            if (fired == 0) runtime.send(fire); // aynı komut ikinci mermi üretmemeli
                            fired++;
                        }
                        if (results == 3 && state.simTime >= 2.4 && !stopped) {
                            runtime.send(new SimulationMessages.Control(state.runId, ++sequence, "STOP", 0, 0));
                            stopped = true;
                        }
                    }
                    Thread.sleep(5);
                }
                if (bulletIds.size() != 3 || !sawTarget || results != 3)
                    throw new AssertionError("Nesne kimlikleri / ilk değerler eksik");
                System.out.println("MOTION_AND_IDENTITIES_OK");
            }
        }
    }
}
