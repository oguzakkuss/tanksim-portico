import java.util.HashSet;
import java.util.Set;

/** Zaman bilgili yollari karsilastirir; hedefi veya mermiyi kendisi degistirmez. */
public final class CollisionDetectionFederate {
    public static void main(String[] args) throws Exception {
        try (FederateRuntime runtime = new FederateRuntime("Collision", false)) {
            runtime.start();
            SimulationStateData state = SimulationStateData.empty();
            String stateKey = null;
            Set<Long> acceptedShots = new HashSet<>();
            long lastReady = 0;
            long started = System.nanoTime();
            boolean everRunning = false;
            boolean stopping = false;

            while (runtime.isAlive() && !stopping) {
                runtime.checkFailure();
                FederateRuntime.Event event;
                while ((event = runtime.poll()) != null) {
                    if (event.kind == FederateRuntime.Kind.STATE) {
                        if (stateKey == null) stateKey = event.objectKey;
                        if (!stateKey.equals(event.objectKey)) continue;
                        state = state.merge(event.state);
                        if (state.phase == SimulationStateData.Phase.RUNNING) everRunning = true;
                        if (state.phase == SimulationStateData.Phase.STOPPING
                                || state.phase == SimulationStateData.Phase.ERROR) {
                            stopping = true;
                            break;
                        }
                    } else if (event.kind == FederateRuntime.Kind.REMOVED
                            && "STATE".equals(event.objectType)
                            && event.objectKey.equals(stateKey)) {
                        stopping = true;
                        break;
                    } else if (event.kind == FederateRuntime.Kind.MESSAGE) {
                        Object message = event.message;
                        if (message instanceof SimulationMessages.Trajectories) {
                            SimulationMessages.Trajectories paths = (SimulationMessages.Trajectories) message;
                            SimulationMessages.Shot shot = paths.shot;
                            if (!shot.runId.equals(state.runId) || !acceptedShots.add(shot.shotId)) continue;
                            runtime.compute(() -> {
                                try {
                                    return TrajectoryMath.collide(paths);
                                } catch (Exception e) {
                                    return new SimulationMessages.Result(shot.runId, shot.shotId,
                                        "ERROR", shot.t0, "Collision: " + e.getClass().getSimpleName()
                                            + ": " + e.getMessage());
                                }
                            });
                        } else if (message instanceof SimulationMessages.Result) {
                            SimulationMessages.Result result = (SimulationMessages.Result) message;
                            if (result.runId.equals(state.runId)) {
                                runtime.send(result);
                                System.out.println("Collision: atis " + result.shotId + " "
                                    + result.status + ", t=" + result.eventTime);
                            }
                        }
                    }
                }

                long now = System.nanoTime();
                if (!stopping && runtime.isReady() && state.isComplete()
                        && state.phase == SimulationStateData.Phase.STARTING
                        && now - lastReady >= 500_000_000L) {
                    runtime.send(new SimulationMessages.Ready(state.runId, "Collision"));
                    lastReady = now;
                }
                if (!stopping && !everRunning
                        && (now - started) / 1e9 > SimulationConfig.STARTUP_TIMEOUT_SECONDS) {
                    throw new IllegalStateException("Collision: baslangic zaman asimi.");
                }
                Thread.sleep(5);
            }
            runtime.checkFailure();
        }
        System.out.println("Collision kapandi.");
    }
}
