import java.util.HashSet;
import java.util.Set;

/** Gercek nesnelere dokunmadan, atis aninin kopyasindan gelecek yolu hesaplar. */
public final class PredictionFederate {
    public static void main(String[] args) throws Exception {
        try (FederateRuntime runtime = new FederateRuntime("Prediction", false)) {
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
                        if (message instanceof SimulationMessages.Shot) {
                            SimulationMessages.Shot shot = (SimulationMessages.Shot) message;
                            if (!shot.runId.equals(state.runId) || !acceptedShots.add(shot.shotId)) continue;
                            System.out.println("Prediction: atis " + shot.shotId + " hesaplaniyor.");
                            // RTI thread'i hesap boyunca callback pompalamaya devam eder.
                            runtime.compute(() -> {
                                try {
                                    return TrajectoryMath.predict(shot);
                                } catch (Exception e) {
                                    return new SimulationMessages.Result(shot.runId, shot.shotId,
                                        "ERROR", shot.t0, "Prediction: " + e.getClass().getSimpleName()
                                            + ": " + e.getMessage());
                                }
                            });
                        } else if (message instanceof SimulationMessages.Trajectories) {
                            SimulationMessages.Trajectories paths = (SimulationMessages.Trajectories) message;
                            if (paths.shot.runId.equals(state.runId)) {
                                runtime.send(paths);
                                System.out.println("Prediction: atis " + paths.shot.shotId
                                    + ", parca=" + paths.targetSegments.size());
                            }
                        } else if (message instanceof SimulationMessages.Result) {
                            SimulationMessages.Result error = (SimulationMessages.Result) message;
                            if (error.runId.equals(state.runId)) runtime.send(error);
                        }
                    }
                }

                long now = System.nanoTime();
                if (!stopping && runtime.isReady() && state.isComplete()
                        && state.phase == SimulationStateData.Phase.STARTING
                        && now - lastReady >= 500_000_000L) {
                    runtime.send(new SimulationMessages.Ready(state.runId, "Prediction"));
                    lastReady = now;
                }
                if (!stopping && !everRunning
                        && (now - started) / 1e9 > SimulationConfig.STARTUP_TIMEOUT_SECONDS) {
                    throw new IllegalStateException("Prediction: baslangic zaman asimi.");
                }
                Thread.sleep(5);
            }
            runtime.checkFailure();
        }
        System.out.println("Prediction kapandi.");
    }
}
