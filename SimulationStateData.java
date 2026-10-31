public final class SimulationStateData {

    public enum Phase {
        STARTING(0),
        RUNNING(1),
        WAITING_FOR_PREDICTION(2),
        STOPPING(3),
        ERROR(4);

        public final int code;

        Phase(int code) {
            this.code = code;
        }

        public static Phase fromCode(int code) {
            for (Phase value : values()) {
                if (value.code == code) {
                    return value;
                }
            }

            throw new IllegalArgumentException(
                "Bilinmeyen phase kodu: " + code
            );
        }
    }

    // null: Alan henüz gelmedi veya bu güncellemede bulunmuyor.
    public final String runId;
    public final Double simTime;
    public final Phase phase;
    public final Double mapWidth;
    public final Double mapHeight;
    public final Double stepSeconds;

    public final EntityId targetEntityId;
    public final EntityId playerEntityId;


    public SimulationStateData(
            String runId,
            Double simTime,
            Phase phase,
            Double mapWidth,
            Double mapHeight,
            Double stepSeconds,
            EntityId targetEntityId,
            EntityId playerEntityId) {

        this.runId = runId;
        this.simTime = simTime;
        this.phase = phase;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.stepSeconds = stepSeconds;
        this.targetEntityId = targetEntityId;
        this.playerEntityId = playerEntityId;
    }

    public static SimulationStateData empty() {
        return new SimulationStateData(
            null, null, null, null, null, null, null, null
        );
    }

    public boolean isComplete() {
        return runId != null
            && simTime != null
            && phase != null
            && mapWidth != null
            && mapHeight != null
            && stepSeconds != null
            && targetEntityId != null
            && playerEntityId != null;
    }

    public SimulationStateData merge(SimulationStateData patch) {
        return new SimulationStateData(
            patch.runId != null ? patch.runId : runId,
            patch.simTime != null ? patch.simTime : simTime,
            patch.phase != null ? patch.phase : phase,
            patch.mapWidth != null ? patch.mapWidth : mapWidth,
            patch.mapHeight != null ? patch.mapHeight : mapHeight,
            patch.stepSeconds != null ? patch.stepSeconds : stepSeconds,
            patch.targetEntityId != null ? patch.targetEntityId : targetEntityId,
            patch.playerEntityId != null ? patch.playerEntityId : playerEntityId
        );
    }
}