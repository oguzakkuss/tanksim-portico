import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import java.util.regex.*;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Gerçek run.ps1 ile iki koşu açar; ilk koşunun sonunda yeniden başlatır,
 * ikincinin sonunda pencereyi kapatır. Native tuş/fare otomasyonu kullanmaz:
 * yalnız test JVM'lerine yüklenen agent, Swing düğmesinin kendi action'ını EDT'de çağırır.
 * Ürün koduna test bayrağı veya otomatik yeniden başlatma davranışı eklenmez.
 */
public final class RestartTests {
    private static final String RESTART_TEXT = "Yeniden başlat";
    private static final List<String> FEDERATES = Arrays.asList(
        "TankFederate", "PredictionFederate", "CollisionDetectionFederate", "DisplayFederate");

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        String federation = "TankRestart_" + UUID.randomUUID().toString().replace("-", "");
        Path control = Files.createDirectories(root.resolve("build/restart-tests").resolve(federation));
        Path agent = createAgent(control);
        Path output = control.resolve("launcher.log");
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile",
            "-ExecutionPolicy", "Bypass", "-File", root.resolve("run.ps1").toString(),
            "-Demo", "-Federation", federation);
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        String existing = builder.environment().getOrDefault("JAVA_TOOL_OPTIONS", "");
        // JAVA_TOOL_OPTIONS içindeki tırnaklar, boşluk içeren mutlak yolları tek seçenek tutar.
        String agentOption = "-javaagent:" + agent + "=" + control;
        require(!agentOption.contains("\""), "Agent yolunda çift tırnak desteklenmiyor");
        builder.environment().put("JAVA_TOOL_OPTIONS", existing + " \"" + agentOption + "\"");

        Process launcher = builder.start();
        Map<Long, TrackedProcess> descendants = new LinkedHashMap<>();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            while (launcher.isAlive()) {
                // Global Java süreçlerini aramayız; yalnız başlattığımız launcher'ın çocukları.
                try (Stream<ProcessHandle> handles = launcher.descendants()) {
                    handles.forEach(handle -> descendants.computeIfAbsent(handle.pid(),
                        ignored -> new TrackedProcess(handle)));
                }
                require(System.nanoTime() < deadline, "Restart testi zaman aşımı; logs=" + output);
                Thread.sleep(100);
            }
            launcher.waitFor();
            // Windows PowerShell konsol çıktısı OEM kod sayfasıyla gelebilir.
            // Yalnız hata açıklamasında kullanılır; uygulama/CSV günlükleri UTF-8'dir.
            if (launcher.exitValue() != 0) throw new AssertionError("Launcher başarısız:\n"
                + new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
            require(!Files.exists(control.resolve("agent-error.txt")),
                "Swing test agent hatası: " + readIfExists(control.resolve("agent-error.txt")));
            require(Files.exists(control.resolve("display-001.restart")), "İlk koşuda düğme çalıştırılmadı");
            require(Files.exists(control.resolve("display-002.close")), "İkinci koşuda pencere kapatılmadı");
            require(!Files.exists(control.resolve("display-003.started")), "İstenmeyen üçüncü koşu açıldı");

            Path session = findSession(root.resolve("logs"), federation);
            Set<String> runIds = new HashSet<>();
            Set<Long> pids = new HashSet<>();
            for (int round = 1; round <= 2; round++) {
                Path folder = session.resolve(String.format(Locale.ROOT, "round-%03d", round));
                String tank = read(folder.resolve("TankFederate.out.log"));
                String display = read(folder.resolve("DisplayFederate.out.log"));
                require(tank.contains("all_four_ready=true"), "Dört federe hazır olmadı: " + folder);
                Matcher run = Pattern.compile("STARTING run=([^\\s]+)").matcher(tank);
                require(run.find() && runIds.add(run.group(1)), "Yeni çalışma kimliği üretilmedi: " + folder);
                require(display.contains("PHASE=STARTING t=0.000"), "Simülasyon saati sıfırlanmadı: " + folder);
                require(tank.contains("SHOT id=1 "), "İlk atış kimliği sıfırlanmadı: " + folder);
                require(!tank.contains("SHOT id=2 "), "Beklenmeyen ikinci atış: " + folder);
                require(tank.contains("FINISHED phase=STOPPING"), "Koşu normal bitmedi: " + folder);
                checkExits(folder.resolve("process-exits.csv"), round, pids);
            }
            for (TrackedProcess child : descendants.values()) {
                require(!child.isSameProcessAlive(), "Launcher çocuğu kapanmadı: PID=" + child.handle.pid());
            }
            System.out.println("RestartTests PASS: two_runs=true new_runId=true time_reset=true "
                + "shot_reset=true no_orphans=true logs=" + session);
        } finally {
            // Hata/timeout olsa bile diğer çalışmalara dokunmadan kendi süreç ağacımızı kapatırız.
            if (launcher.isAlive()) {
                try (Stream<ProcessHandle> handles = launcher.descendants()) {
                    handles.forEach(handle -> descendants.computeIfAbsent(handle.pid(),
                        ignored -> new TrackedProcess(handle)));
                }
            }
            for (TrackedProcess child : descendants.values()) {
                if (child.isSameProcessAlive()) child.handle.destroyForcibly();
            }
            if (launcher.isAlive()) launcher.destroyForcibly();
            launcher.waitFor(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (TrackedProcess child : descendants.values()) {
                while (child.isSameProcessAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            }
        }
    }

    /** Agent sadece testin Display JVM'inde çalışır; Tank, javac ve diğer JVM'lere dokunmaz. */
    public static void premain(String argument, Instrumentation ignored) {
        String command = System.getProperty("sun.java.command", "");
        if (!command.equals("DisplayFederate") && !command.startsWith("DisplayFederate ")) return;
        Path control = Paths.get(argument);
        Thread automation = new Thread(() -> {
            try { driveWindow(control); }
            catch (Throwable error) {
                error.printStackTrace();
                try {
                    StringWriter text = new StringWriter();
                    error.printStackTrace(new PrintWriter(text));
                    Files.writeString(control.resolve("agent-error.txt"), text.toString(), StandardCharsets.UTF_8);
                } catch (Exception reportingFailure) { reportingFailure.printStackTrace(); }
                SwingUtilities.invokeLater(() -> {
                    for (Frame frame : Frame.getFrames()) if (isSimulationFrame(frame))
                        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                });
            }
        }, "RestartTests-agent");
        automation.setDaemon(true);
        automation.start();
    }

    private static void driveWindow(Path control) throws Exception {
        int round = 1;
        while (true) {
            try {
                Files.writeString(control.resolve(String.format(Locale.ROOT, "display-%03d.started", round)),
                    Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                break;
            } catch (FileAlreadyExistsException alreadyStarted) { round++; }
        }
        require(round <= 2, "İki koşudan fazlası başlatıldı");
        final int currentRound = round;
        boolean[] disabledSeen = {false}, actionDone = {false};
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!actionDone[0]) {
            require(System.nanoTime() < deadline, "Yeniden başlat düğmesi etkin olmadı; koşu=" + round);
            SwingUtilities.invokeAndWait(() -> {
                for (Frame frame : Frame.getFrames()) {
                    if (!isSimulationFrame(frame) || !frame.isDisplayable()) continue;
                    // Pencere oluşturulur ve normal EDT/timer akışı korunur; masaüstünü meşgul etmez.
                    if (frame.isVisible()) frame.setVisible(false);
                    JButton restart = findButton((Container) frame, RESTART_TEXT);
                    if (restart == null) continue;
                    if (!restart.isEnabled()) { disabledSeen[0] = true; continue; }
                    require(disabledSeen[0], "Restart oyun sürerken devre dışı görülmedi");
                    // Action sonrasında main System.exit çağırabilir; test izini önce kaydet.
                    try {
                        Files.writeString(control.resolve(String.format(Locale.ROOT,
                            "display-%03d.%s", currentRound, currentRound == 1 ? "restart" : "close")),
                            "disabled_before_terminal=true");
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                    if (currentRound == 1) restart.doClick(0);
                    else frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                    actionDone[0] = true;
                    break;
                }
            });
            if (!actionDone[0]) Thread.sleep(50);
        }
    }

    private static boolean isSimulationFrame(Frame frame) {
        return frame instanceof JFrame && frame.getTitle().startsWith("Tank Simülasyonu");
    }

    private static JButton findButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) return (JButton) child;
            if (child instanceof Container) {
                JButton found = findButton((Container) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Path createAgent(Path control) throws Exception {
        Path classes = Paths.get(RestartTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(Files.isDirectory(classes), "Test build klasöründen çalıştırılmalı");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", "RestartTests");
        Path destination = control.resolve("restart-agent.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(destination), manifest);
                DirectoryStream<Path> files = Files.newDirectoryStream(classes, "RestartTests*.class")) {
            for (Path file : files) {
                jar.putNextEntry(new JarEntry(file.getFileName().toString()));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        return destination;
    }

    private static Path findSession(Path logs, String federation) throws Exception {
        try (Stream<Path> paths = Files.walk(logs, 3)) {
            Iterator<Path> candidates = paths.filter(path -> path.getFileName().toString().equals("TankFederate.out.log"))
                .filter(path -> path.getParent().getFileName().toString().equals("round-001")).iterator();
            while (candidates.hasNext()) {
                Path candidate = candidates.next();
                if (read(candidate).contains(federation)) return candidate.getParent().getParent();
            }
        }
        throw new AssertionError("Test oturumunun günlükleri bulunamadı: " + federation);
    }

    private static void checkExits(Path csv, int round, Set<Long> allPids) throws Exception {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        require(lines.size() == 5, "Dört süreç çıkışı bekleniyordu: " + csv);
        String[] headers = csvFields(lines.get(0).replace("\ufeff", ""));
        int roleColumn = Arrays.asList(headers).indexOf("Federate");
        int pidColumn = Arrays.asList(headers).indexOf("ProcessId");
        int exitColumn = Arrays.asList(headers).indexOf("ExitCode");
        require(roleColumn >= 0 && pidColumn >= 0 && exitColumn >= 0, "Çıkış CSV başlıkları eksik");
        Set<String> roles = new HashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] values = csvFields(lines.get(i));
            String role = values[roleColumn];
            int expected = round == 1 && role.equals("DisplayFederate") ? 10 : 0;
            require(Integer.parseInt(values[exitColumn]) == expected, "Beklenmeyen çıkış: " + lines.get(i));
            long pid = Long.parseLong(values[pidColumn]);
            require(allPids.add(pid), "Yeni JVM için farklı PID bekleniyordu: " + pid);
            require(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Eski federe hâlâ açık: " + pid);
            require(roles.add(role), "Tekrarlanan rol: " + role);
        }
        require(roles.equals(new HashSet<>(FEDERATES)), "Eksik/fazla federeler: " + roles);
    }

    // Bu CSV yalnız sınıf adı, PID ve sayısal çıkış kodu içerir; alanların içinde virgül yoktur.
    private static String[] csvFields(String line) {
        String[] fields = line.split(",", -1);
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i].trim();
            if (field.startsWith("\"") && field.endsWith("\"")) field = field.substring(1, field.length() - 1);
            fields[i] = field;
        }
        return fields;
    }

    private static final class TrackedProcess {
        final ProcessHandle handle;
        final Optional<Instant> started;
        TrackedProcess(ProcessHandle handle) { this.handle = handle; started = handle.info().startInstant(); }
        boolean isSameProcessAlive() {
            return handle.isAlive() && started.equals(handle.info().startInstant());
        }
    }

    private static String read(Path path) throws Exception { return Files.readString(path, StandardCharsets.UTF_8); }
    private static String readIfExists(Path path) throws Exception { return Files.exists(path) ? read(path) : ""; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
