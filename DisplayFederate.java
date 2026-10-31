import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/**
 * Display yalnızca aldığı gerçek durumu gösterir ve kullanıcı komutlarını yollar.
 * RTI/callback/worker zinciri FederateRuntime içindedir. Bu main döngüsü yerel
 * görünüm modelinin tek yazarıdır; Swing EDT yalnız değişmez kopyaları çizer.
 */
public final class DisplayFederate {
    public enum CommandKind { STEER, MOVE_STOP, FIRE, STOP, CLOSE, RESTART }
    // Yalnız run.ps1 tarafından yorumlanır; federeler arası bir HLA mesajı değildir.
    private static final int RESTART_EXIT_CODE = 10;

    public static final class UiCommand {
        public final CommandKind kind;
        public final double x, y;

        public UiCommand(CommandKind kind, double x, double y) {
            this.kind = kind;
            this.x = x;
            this.y = y;
        }
    }

    private static final class EntityView {
        final String objectKey, type;
        final EntityId id;
        final SpatialState spatial;

        EntityView(String objectKey, String type, EntityId id, SpatialState spatial) {
            this.objectKey = objectKey;
            this.type = type;
            this.id = id;
            this.spatial = spatial;
        }

        EntityView merge(FederateRuntime.Event event) {
            return new EntityView(objectKey,
                event.objectType != null ? event.objectType : type,
                event.entityId != null ? event.entityId : id,
                event.spatial != null ? event.spatial : spatial);
        }
    }

    private final Map<String, EntityView> views = new LinkedHashMap<>();
    private final Queue<UiCommand> commands = new ConcurrentLinkedQueue<>();
    private SimulationStateData state = SimulationStateData.empty();
    private SimulationWindow window;
    private String stateObjectKey;
    private long commandId;
    private String detail = "Federasyona bağlanılıyor; Tank ve diğer federeler bekleniyor.";
    private boolean closeRequested, stopSent, autoFired;
    private boolean restartAvailable;
    private long stopRequestedAt;
    private double demoStopAt = Double.POSITIVE_INFINITY;

    public static void main(String[] args) throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        boolean demo = false;
        for (String arg : args) {
            if ("--headless".equals(arg)) headless = true;
            else if ("--demo".equals(arg)) demo = true;
            else if ("--help".equals(arg)) {
                System.out.println("DisplayFederate [--headless] [--demo]");
                System.out.println("-Dtanksim.autoFire=true : ilk RUNNING durumunda tek atis");
                System.out.println("-Dtanksim.autoStopSeconds=N : sim zamani N olunca STOP");
                return;
            } else throw new IllegalArgumentException("Bilinmeyen secenek: " + arg);
        }
        int exitCode = new DisplayFederate().run(headless, demo);
        if (exitCode != 0) System.exit(exitCode);
    }

    private int run(boolean headless, boolean demo) throws Exception {
        boolean autoFire = demo || Boolean.getBoolean("tanksim.autoFire");
        double autoStopSeconds = Double.parseDouble(System.getProperty(
            "tanksim.autoStopSeconds", demo ? "10" : "0"));
        if (!Double.isFinite(autoStopSeconds) || autoStopSeconds < 0)
            throw new IllegalArgumentException("autoStopSeconds sonlu ve >= 0 olmali");

        if (!headless) {
            SwingUtilities.invokeAndWait(() -> {
                window = new SimulationWindow(commands);
                window.showWindow();
            });
        }

        FederateRuntime runtime = new FederateRuntime("Display", false);
        Exception failure = null;
        boolean terminal = false;
        try {
            runtime.start();
            long startupAt = System.nanoTime();
            boolean simulationStarted = false;
            long nextReadyAt = 0, nextPaintAt = 0;
            while (!terminal) {
                runtime.checkFailure();
                long now = System.nanoTime();

                FederateRuntime.Event event;
                while ((event = runtime.poll()) != null) {
                    apply(event, demo);
                    if (state.phase == SimulationStateData.Phase.STOPPING
                            || state.phase == SimulationStateData.Phase.ERROR) {
                        terminal = true;
                        // Kapanıştaki sahiplik silmeleri son sahneyi boşaltmasın.
                        break;
                    }
                }

                if (state.phase == SimulationStateData.Phase.RUNNING
                        || state.phase == SimulationStateData.Phase.WAITING_FOR_PREDICTION)
                    simulationStarted = true;
                if (!terminal && !simulationStarted && (now - startupAt) / 1_000_000_000.0
                        >= SimulationConfig.STARTUP_TIMEOUT_SECONDS)
                    throw new IllegalStateException("Baslangic zaman asimi: Tank ve tum federeler hazir olmadi");

                if (state.isComplete() && runtime.isReady()
                        && state.phase == SimulationStateData.Phase.STARTING
                        && now >= nextReadyAt) {
                    runtime.send(new SimulationMessages.Ready(state.runId, "Display"));
                    nextReadyAt = now + TimeUnit.MILLISECONDS.toNanos(500);
                }

                UiCommand command;
                while ((command = commands.poll()) != null) {
                    if (command.kind == CommandKind.CLOSE) closeRequested = true;
                    if (!terminal) handleCommand(runtime, command);
                }

                if (!terminal && state.phase == SimulationStateData.Phase.RUNNING) {
                    // Otomatik test yalnız ateş komutu verir; nişan alma hesabı yapmaz.
                    if (autoFire && !autoFired && !stopSent) {
                        autoFired = true;
                        handleCommand(runtime, new UiCommand(CommandKind.FIRE, 0, 0));
                    }
                    if (state.simTime != null && !stopSent
                            && ((autoStopSeconds > 0 && state.simTime >= autoStopSeconds)
                                || state.simTime >= demoStopAt)) {
                        handleCommand(runtime, new UiCommand(CommandKind.STOP, 0, 0));
                    }
                }

                if (now >= nextPaintAt || terminal) {
                    publishView(!terminal);
                    nextPaintAt = now + TimeUnit.MILLISECONDS.toNanos(33);
                }

                // Kullanıcı pencereyi kapattıysa STOP'un işlenmesi için kısa süre tanı.
                // Tank kaybolmuş olsa da pencere kapatma sonsuza kadar beklemez.
                if (closeRequested && state.runId == null) terminal = true;
                if (!terminal && stopSent && (now - stopRequestedAt) / 1_000_000_000.0
                        >= SimulationConfig.SHUTDOWN_TIMEOUT_SECONDS)
                    throw new IllegalStateException("STOP yaniti zaman asimi; RTI baglantisi kapatiliyor");

                if (!runtime.isAlive()) {
                    runtime.checkFailure();
                    if (!terminal) throw new IllegalStateException("RTI baglantisi sona erdi");
                }
                if (!terminal) Thread.sleep(5);
            }
        } catch (Exception e) {
            failure = e;
            localError("Bağlantı/işlem hatası: " + e.getMessage());
            System.err.println("[Display] ERROR " + e);
        } finally {
            boolean runtimeClosed = false;
            try {
                runtime.close();
                runtimeClosed = true;
            } catch (Exception e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
                localError("Kapanış hatası: " + e.getMessage());
            }
            // Terminal Phase gelmesi tek başına yeterli değildir: önce RTI ve worker kapanır.
            // Yeniden açma işini dört JVM'i yöneten run.ps1 üstlenir.
            restartAvailable = runtimeClosed && !headless && !closeRequested
                && Boolean.getBoolean("tanksim.managedRestart");
            publishView(false);
            if (closeRequested && window != null) window.closeWindow();
        }

        System.out.println("[Display] RTI baglantisi kapandi.");
        if (headless && failure != null) throw failure;
        if (headless && state.phase == SimulationStateData.Phase.ERROR)
            throw new IllegalStateException("Tank federesi ERROR durumuna gecti");
        if (!headless && !closeRequested) return awaitEndCommand();
        return 0;
    }

    private int awaitEndCommand() throws InterruptedException {
        // RTI artık kapalıdır; son sahne açıkken yalnız yerel kapat/restart komutları beklenir.
        while (!closeRequested) {
            boolean restartRequested = false;
            UiCommand command;
            while ((command = commands.poll()) != null) {
                if (command.kind == CommandKind.CLOSE) closeRequested = true;
                if (command.kind == CommandKind.RESTART && restartAvailable) restartRequested = true;
            }
            if (closeRequested) break;
            if (restartRequested) {
                restartAvailable = false;
                System.out.println("[Display] RESTART_REQUESTED");
                window.closeWindow();
                return RESTART_EXIT_CODE;
            }
            Thread.sleep(20);
        }
        window.closeWindow();
        return 0;
    }

    private void apply(FederateRuntime.Event event, boolean demo) {
        switch (event.kind) {
            case DISCOVERED:
            case ENTITY:
                if ("STATE".equals(event.objectType)) {
                    if (stateObjectKey == null) stateObjectKey = event.objectKey;
                } else if (event.objectKey != null) {
                    EntityView old = views.get(event.objectKey);
                    views.put(event.objectKey, old == null
                        ? new EntityView(event.objectKey, event.objectType,
                            event.entityId, event.spatial)
                        : old.merge(event));
                }
                break;
            case REMOVED:
                if (stateObjectKey != null && stateObjectKey.equals(event.objectKey)
                        && state.phase != SimulationStateData.Phase.STOPPING) {
                    localError("Tank federesinin SimulationState nesnesi beklenmedik şekilde silindi.");
                    System.err.println("[Display] ERROR: SimulationState removed unexpectedly");
                }
                views.remove(event.objectKey);
                break;
            case STATE:
                if (event.state != null) {
                    if (stateObjectKey == null) stateObjectKey = event.objectKey;
                    if (!Objects.equals(stateObjectKey, event.objectKey)) break;
                    SimulationStateData.Phase oldPhase = state.phase;
                    String oldRunId = state.runId;
                    state = state.merge(event.state);
                    if (oldRunId != null && !Objects.equals(oldRunId, state.runId)) {
                        autoFired = false;
                        stopSent = false;
                        demoStopAt = Double.POSITIVE_INFINITY;
                    }
                    if (oldPhase != state.phase) {
                        detail = phaseText(state.phase);
                        System.out.printf(Locale.ROOT, "[Display] PHASE=%s t=%.3f%n",
                            state.phase, state.simTime == null ? 0 : state.simTime);
                    }
                }
                break;
            case MESSAGE:
                if (event.message instanceof SimulationMessages.Result) {
                    SimulationMessages.Result result = (SimulationMessages.Result) event.message;
                    if (!Objects.equals(state.runId, result.runId)) break;
                    if ("HIT".equals(result.status)) {
                        detail = String.format(Locale.ROOT,
                            "Atış %d: t=%.3f için temas öngörüldü; henüz uygulanmadı.",
                            result.shotId, result.eventTime);
                    } else if ("NO_HIT".equals(result.status)) {
                        detail = String.format(Locale.ROOT,
                            "Atış %d: hedefe temas yok; haritadan çıkış t=%.3f.",
                            result.shotId, result.eventTime);
                        if (demo && Double.isFinite(result.eventTime)) demoStopAt = result.eventTime;
                    } else {
                        detail = "Atış " + result.shotId + ": " + result.status
                            + (result.detail == null ? "" : " — " + result.detail);
                    }
                    System.out.printf(Locale.ROOT,
                        "[Display] RESULT shot=%d status=%s eventTime=%.3f detail=%s%n",
                        result.shotId, result.status, result.eventTime, result.detail);
                }
                break;
            default: break;
        }
    }

    private void handleCommand(FederateRuntime runtime, UiCommand command) {
        // RESTART yalnız oyun sonundaki yerel döngüde işlenir; ControlCommand olarak gönderilmez.
        if (command.kind == CommandKind.RESTART) return;
        if (command.kind == CommandKind.STEER
                && (!Double.isFinite(command.x) || !Double.isFinite(command.y)
                    || command.x == 0.0 && command.y == 0.0)) {
            detail = "Yön sıfır olamaz ve sonlu olmalı; durmak için Tankı durdur.";
            System.err.println("[Display] Gecersiz STEER reddedildi; hareket komutu gonderilmedi.");
            return;
        }
        if (state.runId == null || !runtime.isReady()) return;
        boolean stopping = command.kind == CommandKind.STOP || command.kind == CommandKind.CLOSE;
        if (stopping) {
            if (stopSent) return;
            stopSent = true;
            stopRequestedAt = System.nanoTime();
        } else {
            if (stopSent) return;
            if (command.kind == CommandKind.FIRE
                    && state.phase != SimulationStateData.Phase.RUNNING) return;
            if (state.phase != SimulationStateData.Phase.RUNNING
                    && state.phase != SimulationStateData.Phase.WAITING_FOR_PREDICTION) return;
        }
        String action = stopping ? "STOP" : command.kind.name();
        runtime.send(new SimulationMessages.Control(state.runId, ++commandId,
            action, command.x, command.y));
        System.out.printf(Locale.ROOT, "[Display] COMMAND id=%d action=%s x=%.2f y=%.2f%n",
            commandId, action, command.x, command.y);
    }

    private void publishView(boolean connected) {
        if (window == null) return;
        List<RenderSnapshot.Entity> entities = new ArrayList<>();
        for (EntityView view : views.values()) {
            if (view.spatial == null || "STATE".equals(view.type)) continue;
            boolean player = view.id != null && view.id.equals(state.playerEntityId);
            boolean target = view.id != null && view.id.equals(state.targetEntityId);
            boolean bullet = "BULLET".equals(view.type);
            SpatialState s = view.spatial;
            // Bu sahnenin yerel düzlemi: ECEF Y -> harita X, ECEF Z -> harita Y.
            double headingX = Math.sin(s.psi) * Math.cos(s.theta);
            double headingY = -Math.sin(s.theta);
            entities.add(new RenderSnapshot.Entity(view.objectKey,
                view.id == null ? view.objectKey : view.id.toString(),
                s.y, s.z, s.vy, s.vz, headingX, headingY,
                player, target, bullet, s.frozen));
        }
        window.render(new RenderSnapshot(state, entities, detail, connected, stopSent, restartAvailable));
    }

    private static String phaseText(SimulationStateData.Phase phase) {
        if (phase == null) return "Simülasyon bilgisi bekleniyor.";
        switch (phase) {
            case STARTING: return "Dört federenin hazır olması bekleniyor.";
            case RUNNING: return "Simülasyon çalışıyor.";
            case WAITING_FOR_PREDICTION: return "Yörünge ve çarpışma hesabı bekleniyor; simülasyon saati durdu.";
            case STOPPING: return "Simülasyon sona erdi; son görünüm korunuyor.";
            case ERROR: return "Simülasyon hata nedeniyle durdu; federe günlüklerini inceleyin.";
            default: return phase.name();
        }
    }

    private void localError(String message) {
        state = state.merge(new SimulationStateData(null, null,
            SimulationStateData.Phase.ERROR, null, null, null, null, null));
        detail = message;
    }
}
