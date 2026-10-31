import java.util.List;
import java.util.Objects;

/** RTI'den bagimsiz, degistirilemeyen uygulama mesajlari. */
public final class SimulationMessages {
    private SimulationMessages() { }

    public static final class Ready {
        public final String runId, role;
        public Ready(String runId, String role) {
            this.runId = Objects.requireNonNull(runId);
            this.role = Objects.requireNonNull(role);
        }
    }

    public static final class Control {
        public final String runId, action;
        public final long commandId;
        public final double x, y;
        public Control(String runId, long commandId, String action, double x, double y) {
            this.runId = Objects.requireNonNull(runId);
            this.commandId = commandId;
            this.action = Objects.requireNonNull(action);
            this.x = x;
            this.y = y;
        }
    }

    public static final class Shot {
        public final String runId;
        public final long shotId;
        public final EntityId bulletId;
        public final double t0, tx, ty, tvx, tvy, bx, by, bvx, bvy;
        public final double width, height, targetRadius, bulletRadius;
        public Shot(String runId, long shotId, EntityId bulletId, double t0,
                    double tx, double ty, double tvx, double tvy,
                    double bx, double by, double bvx, double bvy,
                    double width, double height, double targetRadius, double bulletRadius) {
            this.runId = Objects.requireNonNull(runId);
            this.shotId = shotId;
            this.bulletId = Objects.requireNonNull(bulletId);
            this.t0 = t0;
            this.tx = tx;
            this.ty = ty;
            this.tvx = tvx;
            this.tvy = tvy;
            this.bx = bx;
            this.by = by;
            this.bvx = bvx;
            this.bvy = bvy;
            this.width = width;
            this.height = height;
            this.targetRadius = targetRadius;
            this.bulletRadius = bulletRadius;
        }
    }

    public static final class Segment {
        public final double start, end, x, y, vx, vy;
        public Segment(double start, double end, double x, double y, double vx, double vy) {
            this.start = start;
            this.end = end;
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
        }
    }

    public static final class Trajectories {
        public final Shot shot;
        public final List<Segment> targetSegments;
        public final double endTime;
        public Trajectories(Shot shot, List<Segment> targetSegments, double endTime) {
            this.shot = Objects.requireNonNull(shot);
            this.targetSegments = List.copyOf(targetSegments);
            this.endTime = endTime;
        }
    }

    public static final class Result {
        public final String runId, status, detail;
        public final long shotId;
        public final double eventTime;
        public Result(String runId, long shotId, String status, double eventTime, String detail) {
            this.runId = Objects.requireNonNull(runId);
            this.shotId = shotId;
            this.status = Objects.requireNonNull(status);
            this.eventTime = eventTime;
            this.detail = Objects.requireNonNull(detail);
        }
    }
}
