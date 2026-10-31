import java.util.*;

/**
 * Gerçek dünyanın tek sahibi: tanklar, mermiler ve simülasyon saati burada yaşar.
 * Bütün model değişiklikleri ana thread'dedir. RTI'yi FederateRuntime yönetir;
 * worker'dan yalnız çözülmüş, değişmez mesajlar gelir.
 */
public final class TankFederate {
    static final EntityId TARGET_ID = new EntityId(1, 1, 1);
    static final EntityId PLAYER_ID = new EntityId(1, 1, 2);
    private static final double EPS = 1e-8;
    private final String runId = UUID.randomUUID().toString();
    private final FederateRuntime runtime;
    private final TargetMotion target = new TargetMotion(
            SimulationConfig.TARGET_X, SimulationConfig.TARGET_Y,
            SimulationConfig.TARGET_DIR_X, SimulationConfig.TARGET_DIR_Y,
            SimulationConfig.WIDTH, SimulationConfig.HEIGHT);
    private final PlayerMotion player = new PlayerMotion(
            SimulationConfig.PLAYER_X, SimulationConfig.PLAYER_Y,
            SimulationConfig.PLAYER_DIR_X, SimulationConfig.PLAYER_DIR_Y,
            SimulationConfig.WIDTH, SimulationConfig.HEIGHT);
    private final Map<Long, ActiveBullet> bullets = new LinkedHashMap<>();
    private final Set<String> readyRoles = new HashSet<>();
    private SimulationStateData.Phase phase = SimulationStateData.Phase.STARTING;
    private double simTime;
    private long lastCommandId = -1, nextShotId = 1, nextStepNanos, predictionStartedNanos;
    private int nextEntityNumber = 3;
    private ActiveBullet pending;
    private boolean targetAlive = true;
    private String failure;

    private static final class ActiveBullet {
        final SimulationMessages.Shot shot;
        final BulletMotion motion;
        final double exitTime;
        SimulationMessages.Result result;
        ActiveBullet(SimulationMessages.Shot shot) {
            this.shot = shot;
            motion = new BulletMotion(shot.bx, shot.by, shot.bvx, shot.bvy);
            exitTime = shot.t0 + Math.min(exitDuration(shot.bx, shot.bvx, shot.width),
                                         exitDuration(shot.by, shot.bvy, shot.height));
        }
    }

    TankFederate(FederateRuntime runtime) {
        this.runtime = runtime;
        player.stop(); // İlk kullanıcı yön komutuna kadar tank yerinde bekler.
    }

    public static void main(String[] args) throws Exception {
        try (FederateRuntime runtime = new FederateRuntime("Tank", true)) {
            runtime.start();
            new TankFederate(runtime).run();
        }
    }

    private void run() throws Exception {
        long startup = System.nanoTime();
        while (!runtime.isReady()) {
            runtime.checkFailure();
            if (!runtime.isAlive()) throw new IllegalStateException("Tank RTI başlatılamadı.");
            if (elapsed(startup) > SimulationConfig.STARTUP_TIMEOUT_SECONDS)
                throw new IllegalStateException("RTI başlangıç süresi aşıldı.");
            Thread.sleep(5);
        }
        publishWorld();
        log("STARTING run=" + runId);
        startup = System.nanoTime();
        try {
            while (!terminal()) {
                runtime.checkFailure();
                if (!runtime.isAlive()) throw new IllegalStateException("Tank RTI thread'i kapandı.");
                FederateRuntime.Event event;
                while ((event = runtime.poll()) != null && !terminal()) {
                    if (event.kind == FederateRuntime.Kind.MESSAGE) receive(event.message);
                }
                if (phase == SimulationStateData.Phase.STARTING) {
                    if (readyRoles.containsAll(Arrays.asList("Display", "Prediction", "Collision"))) {
                        phase = SimulationStateData.Phase.RUNNING;
                        resetDeadline();
                        publishState();
                        log("RUNNING all_four_ready=true");
                    } else if (elapsed(startup) > SimulationConfig.STARTUP_TIMEOUT_SECONDS) {
                        fail("Hazır olma süresi aşıldı. Hazır federeler: " + readyRoles);
                    }
                } else if (phase == SimulationStateData.Phase.WAITING_FOR_PREDICTION) {
                    // Duvar saati ilerlerken simTime sabit kalır. Ana döngü çalışır;
                    // STOP ve CollisionResult mesajları bu sırada da işlenir.
                    if (elapsed(predictionStartedNanos) > SimulationConfig.PREDICTION_TIMEOUT_SECONDS)
                        fail("Tahmin/çarpışma yanıt süresi aşıldı; atış=" + pending.shot.shotId);
                } else if (phase == SimulationStateData.Phase.RUNNING
                        && System.nanoTime() >= nextStepNanos) {
                    advanceTo(simTime + SimulationConfig.STEP_SECONDS);
                    nextStepNanos += stepNanos();
                    if (!terminal()) publishWorld();
                }
                Thread.sleep(2);
            }
        } catch (Exception e) {
            fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        } finally {
            if (!terminal()) phase = SimulationStateData.Phase.STOPPING;
            publishState();
            // Terminal Phase'in alıcılara gitmesine kısa bir fırsat verilir.
            // close() ayrıca kalan outbound işleri boşaltıp resign eder.
            Thread.sleep(200);
        }
        log("FINISHED phase=" + phase + " simTime=" + format(simTime));
        if (failure != null) throw new IllegalStateException(failure);
    }

    private void receive(Object message) {
        if (message instanceof SimulationMessages.Ready) {
            SimulationMessages.Ready ready = (SimulationMessages.Ready) message;
            if (runId.equals(ready.runId) && readyRoles.add(ready.role)) log("READY " + ready.role);
        } else if (message instanceof SimulationMessages.Control) {
            control((SimulationMessages.Control) message);
        } else if (message instanceof SimulationMessages.Result) {
            acceptResult((SimulationMessages.Result) message);
        }
    }

    private void control(SimulationMessages.Control command) {
        // Monoton kimlik yeniden gelen komutun ikinci atış üretmesini engeller.
        if (!runId.equals(command.runId) || command.commandId <= lastCommandId) return;
        lastCommandId = command.commandId;
        if ("STOP".equals(command.action)) {
            phase = SimulationStateData.Phase.STOPPING;
            publishState();
            log("STOP_REQUESTED");
        } else if ("MOVE_STOP".equals(command.action)) {
            player.stop();
            publishPlayer();
        } else if (phase == SimulationStateData.Phase.RUNNING
                || phase == SimulationStateData.Phase.WAITING_FOR_PREDICTION) {
            if ("STEER".equals(command.action)) {
                if (!Double.isFinite(command.x) || !Double.isFinite(command.y)
                        || command.x == 0 && command.y == 0) {
                    log("COMMAND_REJECTED direction must be finite and nonzero");
                    return;
                }
                // Beklerken yeni yön kaybolmaz; konum ve simTime yine sabittir.
                player.steer(command.x, command.y);
                publishPlayer();
                log("STEER vx=" + format(player.getVx()) + " vy=" + format(player.getVy()));
            } else if ("FIRE".equals(command.action)
                    && phase == SimulationStateData.Phase.RUNNING) fire();
        }
    }

    private void fire() {
        if (nextEntityNumber > 65535) { fail("EntityIdentifier numaraları tükendi."); return; }
        EntityId bulletId = new EntityId(1, 1, nextEntityNumber++);
        // Tüm snapshot alanları aynı ana thread'de aynı t0 için okunur.
        // Dünya hızının büyüklüğü 50; oyuncu hızı mermi hızına eklenmez.
        SimulationMessages.Shot shot = new SimulationMessages.Shot(
                runId, nextShotId++, bulletId, simTime,
                target.getX(), target.getY(), target.getVx(), target.getVy(),
                player.getX(), player.getY(),
                player.getDirectionX() * SimulationConfig.BULLET_SPEED,
                player.getDirectionY() * SimulationConfig.BULLET_SPEED,
                SimulationConfig.WIDTH, SimulationConfig.HEIGHT,
                SimulationConfig.TARGET_RADIUS, SimulationConfig.BULLET_RADIUS);
        pending = new ActiveBullet(shot);
        bullets.put(shot.shotId, pending);
        phase = SimulationStateData.Phase.WAITING_FOR_PREDICTION;
        predictionStartedNanos = System.nanoTime();
        publishBullet(pending);
        publishState();
        runtime.send(shot);
        log("SHOT id=" + shot.shotId + " t0=" + format(simTime)
                + " bx=" + format(shot.bx) + " by=" + format(shot.by)
                + " bvx=" + format(shot.bvx) + " bvy=" + format(shot.bvy));
        log("WAITING_FOR_PREDICTION simTime=" + format(simTime));
    }

    private void acceptResult(SimulationMessages.Result result) {
        if (!runId.equals(result.runId) || pending == null || result.shotId != pending.shot.shotId) {
            log("RESULT_IGNORED id=" + result.shotId);
            return;
        }
        if ("ERROR".equals(result.status)) { fail("Hesap hatası: " + result.detail); return; }
        if (!("HIT".equals(result.status) || "NO_HIT".equals(result.status))
                || !Double.isFinite(result.eventTime)
                || result.eventTime < pending.shot.t0 - EPS
                || result.eventTime > pending.exitTime + EPS
                || ("NO_HIT".equals(result.status) && Math.abs(result.eventTime - pending.exitTime) > EPS)) {
            fail("Geçersiz CollisionResult: " + result.status + " t=" + result.eventTime);
            return;
        }
        pending.result = result;
        pending = null;
        phase = SimulationStateData.Phase.RUNNING;
        // Beklemede geçen duvar zamanı simülasyona eklenmez.
        resetDeadline();
        publishState();
        log("RESULT id=" + result.shotId + " status=" + result.status
                + " eventTime=" + format(result.eventTime) + " receivedAt=" + format(simTime));
    }

    private void advanceTo(double end) {
        while (phase == SimulationStateData.Phase.RUNNING) {
            double nextEvent = Double.POSITIVE_INFINITY;
            for (ActiveBullet bullet : bullets.values()) nextEvent = Math.min(nextEvent, eventTime(bullet));
            // Tick'in ortasındaki olayda önce tam o ana ilerle, olayı uygula,
            // gerekiyorsa tick'in kalan süresini hesapla.
            double at = Math.min(end, nextEvent);
            double dt = Math.max(0, at - simTime);
            target.update(dt);
            player.update(dt);
            for (ActiveBullet bullet : bullets.values()) bullet.motion.update(dt);
            simTime = at;
            if (nextEvent <= end) {
                ActiveBullet hit = null;
                for (ActiveBullet bullet : bullets.values()) {
                    if (eventTime(bullet) <= simTime + EPS && bullet.result != null
                            && "HIT".equals(bullet.result.status)) { hit = bullet; break; }
                }
                if (hit != null) {
                    publishWorld();
                    runtime.deleteEntity(hit.shot.bulletId);
                    runtime.deleteEntity(TARGET_ID);
                    targetAlive = false;
                    phase = SimulationStateData.Phase.STOPPING;
                    publishState();
                    log("HIT_APPLIED id=" + hit.shot.shotId + " simTime=" + format(simTime)
                            + " separation=" + format(Math.hypot(target.getX() - hit.motion.getX(),
                                                                 target.getY() - hit.motion.getY())));
                    return;
                }
                Iterator<ActiveBullet> iterator = bullets.values().iterator();
                while (iterator.hasNext()) {
                    ActiveBullet bullet = iterator.next();
                    if (bullet.exitTime <= simTime + EPS) {
                        runtime.deleteEntity(bullet.shot.bulletId);
                        iterator.remove();
                        log("BULLET_EXIT id=" + bullet.shot.shotId + " simTime=" + format(simTime));
                    }
                }
            }
            if (simTime >= end) return;
        }
    }

    private static double eventTime(ActiveBullet bullet) {
        return bullet.result != null && "HIT".equals(bullet.result.status)
                ? Math.min(bullet.exitTime, Math.max(bullet.shot.t0, bullet.result.eventTime)) : bullet.exitTime;
    }
    private void publishWorld() {
        if (targetAlive) runtime.publishEntity(TARGET_ID, "TANK", SpatialMapper.fromTarget(target));
        publishPlayer();
        for (ActiveBullet bullet : bullets.values()) publishBullet(bullet);
        publishState();
    }
    private void publishPlayer() {
        runtime.publishEntity(PLAYER_ID, "TANK", SpatialMapper.fromLocal(player.getX(), player.getY(),
                player.getVx(), player.getVy(), player.getDirectionX(), player.getDirectionY()));
    }
    private void publishBullet(ActiveBullet bullet) {
        BulletMotion b = bullet.motion;
        runtime.publishEntity(bullet.shot.bulletId, "BULLET", SpatialMapper.fromLocal(
                b.getX(), b.getY(), b.getVx(), b.getVy(), b.getVx(), b.getVy()));
    }
    private void publishState() {
        runtime.publishState(new SimulationStateData(runId, simTime, phase,
                SimulationConfig.WIDTH, SimulationConfig.HEIGHT, SimulationConfig.STEP_SECONDS,
                TARGET_ID, PLAYER_ID));
    }
    private void fail(String reason) {
        failure = reason;
        phase = SimulationStateData.Phase.ERROR;
        publishState();
        System.err.println("Tank ERROR " + reason);
    }
    private boolean terminal() {
        return phase == SimulationStateData.Phase.STOPPING || phase == SimulationStateData.Phase.ERROR;
    }
    private static double exitDuration(double position, double velocity, double bound) {
        if (velocity > 0) return (bound - position) / velocity;
        if (velocity < 0) return -position / velocity;
        return Double.POSITIVE_INFINITY;
    }
    private void resetDeadline() { nextStepNanos = System.nanoTime() + stepNanos(); }
    private static long stepNanos() { return (long) (SimulationConfig.STEP_SECONDS * 1_000_000_000L); }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000_000.0; }
    private static String format(double value) { return String.format(Locale.ROOT, "%.9f", value); }
    private static void log(String message) { System.out.println("Tank " + message); }
}
