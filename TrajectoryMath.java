import java.util.ArrayList;
import java.util.List;

/** Tahmin ve carpisma hesabi; gercek nesneleri veya sim saatini degistirmez. */
public final class TrajectoryMath {
    private static final int MAX_SEGMENTS = 20_000;
    private static final double MAX_DIMENSION = 1_000_000.0;
    private static final double MAX_SPEED = 1_000_000.0;
    private static final double MAX_HORIZON = 1_000_000.0;
    private static final double MAX_TIME = 1_000_000_000.0;
    private static final double POSITION_EPS = 1e-7;

    private TrajectoryMath() { }

    public static SimulationMessages.Trajectories predict(SimulationMessages.Shot shot) {
        validateShot(shot);
        double horizon = exitDuration(shot);
        double endTime = shot.t0 + horizon;
        double x = shot.tx;
        double y = shot.ty;
        double vx = inwardVelocity(x, shot.tvx, shot.width);
        double vy = inwardVelocity(y, shot.tvy, shot.height);
        double elapsed = 0;
        List<SimulationMessages.Segment> segments = new ArrayList<>();

        // Sinirdan disariya ates edilirse sure sifirdir. t0 temasi yine incelenir.
        if (horizon == 0) {
            segments.add(new SimulationMessages.Segment(shot.t0, endTime, x, y, vx, vy));
        }
        while (elapsed < horizon) {
            if (segments.size() >= MAX_SEGMENTS) {
                throw new IllegalArgumentException("Tahmin cok fazla sekme parcasi uretiyor.");
            }
            double tx = boundaryTime(x, vx, shot.width);
            double ty = boundaryTime(y, vy, shot.height);
            double remaining = horizon - elapsed;
            double dt = Math.min(remaining, Math.min(tx, ty));
            double nextElapsed = Math.min(horizon, elapsed + dt);
            double start = shot.t0 + elapsed;
            double end = shot.t0 + nextElapsed;
            if (dt <= 0 || nextElapsed <= elapsed || end <= start) {
                throw new IllegalArgumentException("Tahmin adimi sayisal hassasiyetin altinda.");
            }
            segments.add(new SimulationMessages.Segment(start, end, x, y, vx, vy));
            x = clamp(x + vx * dt, shot.width);
            y = clamp(y + vy * dt, shot.height);

            // Mevcut nokta modelini koruyoruz: merkez 0..width / 0..height arasinda.
            // Carpisma yaricaplari duvarin konumunu degistirmez.
            double bounceEps = timeTolerance(start, end);
            if (tx <= dt + bounceEps) {
                x = vx > 0 ? shot.width : 0;
                vx = -vx;
            }
            if (ty <= dt + bounceEps) {
                y = vy > 0 ? shot.height : 0;
                vy = -vy;
            }
            elapsed = nextElapsed;
            // Bir sekme ile cikis arasindaki fark double saatine sigmayabilir.
            // Son parca zaten endTime'a ulasmissa sifir sureli kuyruk acma.
            if (end >= endTime) {
                break;
            }
        }
        return new SimulationMessages.Trajectories(shot, segments, endTime);
    }

    public static SimulationMessages.Result collide(SimulationMessages.Trajectories paths) {
        validatePaths(paths);
        SimulationMessages.Shot shot = paths.shot;
        double radius = shot.targetRadius + shot.bulletRadius;

        for (SimulationMessages.Segment segment : paths.targetSegments) {
            double age = segment.start - shot.t0;
            double rx = shot.bx + shot.bvx * age - segment.x;
            double ry = shot.by + shot.bvy * age - segment.y;
            double duration = segment.end - segment.start;

            // Iki cismin konumunu AYNI anda karsilastiriyoruz.
            double distance = Math.hypot(rx, ry);
            double contactEps = 8 * Math.ulp(Math.max(distance, radius));
            if (distance <= radius + contactEps) {
                return hit(shot, segment.start);
            }
            double wx = shot.bvx - segment.vx;
            double wy = shot.bvy - segment.vy;
            double speedSquared = wx * wx + wy * wy;
            if (speedSquared == 0) {
                continue; // Goreli hareket yok ve baslangicta temas da yok.
            }

            double closest = -(rx * wx + ry * wy) / speedSquared;
            if (closest < 0) {
                continue; // Bu parcanin basindan itibaren birbirlerinden uzaklasiyorlar.
            }

            // Goreli dogrunun merkeze uzakligi; capraz carpim yontemi,
            // birbirine yakin buyuk sayilarin cikarilmasindaki kaybi onler.
            double cross = rx * wy - ry * wx;
            double distanceSquared = cross * cross / speedSquared;
            double allowance = radius * radius - distanceSquared;
            double tolerance = 16 * Math.ulp(Math.max(radius * radius, distanceSquared));
            if (allowance < -tolerance) {
                continue;
            }
            double entry = closest - Math.sqrt(Math.max(0, allowance) / speedSquared);
            double timeEps = timeTolerance(segment.start, segment.end);
            if (entry >= -timeEps && entry <= duration + timeEps) {
                return hit(shot, segment.start + Math.max(0, Math.min(duration, entry)));
            }
        }

        return new SimulationMessages.Result(shot.runId, shot.shotId, "NO_HIT",
            paths.endTime, "Mermi merkezi harita sinirina ulasiyor; isabet yok.");
    }

    private static SimulationMessages.Result hit(SimulationMessages.Shot shot, double time) {
        return new SimulationMessages.Result(shot.runId, shot.shotId, "HIT", time,
            "Iki hareketli dairenin ilk temasi.");
    }

    private static void validateShot(SimulationMessages.Shot shot) {
        if (shot == null || shot.runId == null || shot.runId.isEmpty()
                || shot.runId.length() > 200 || shot.bulletId == null || shot.shotId < 0) {
            throw new IllegalArgumentException("Atis kimligi gecersiz.");
        }
        requireFinite(shot.t0, shot.tx, shot.ty, shot.tvx, shot.tvy,
            shot.bx, shot.by, shot.bvx, shot.bvy, shot.width, shot.height,
            shot.targetRadius, shot.bulletRadius);
        if (shot.width <= 0 || shot.height <= 0 || shot.width > MAX_DIMENSION
                || shot.height > MAX_DIMENSION || shot.t0 < 0 || shot.t0 > MAX_TIME
                || shot.targetRadius < 0 || shot.bulletRadius < 0
                || shot.targetRadius > MAX_DIMENSION || shot.bulletRadius > MAX_DIMENSION) {
            throw new IllegalArgumentException("Atis haritasi, saati veya yaricapi gecersiz.");
        }
        requireInside(shot.tx, shot.ty, shot.width, shot.height, 0);
        requireInside(shot.bx, shot.by, shot.width, shot.height, 0);
        if (Math.hypot(shot.tvx, shot.tvy) > MAX_SPEED
                || Math.hypot(shot.bvx, shot.bvy) > MAX_SPEED
                || (shot.bvx == 0 && shot.bvy == 0)) {
            throw new IllegalArgumentException("Atis hizi gecersiz.");
        }
        double duration = exitDuration(shot);
        if (!Double.isFinite(duration) || duration < 0 || duration > MAX_HORIZON
                || shot.t0 + duration > MAX_TIME
                || (duration > 0 && shot.t0 + duration <= shot.t0)) {
            throw new IllegalArgumentException("Atis ufku cok buyuk veya sayisal olarak gecersiz.");
        }
    }

    private static void validatePaths(SimulationMessages.Trajectories paths) {
        if (paths == null) {
            throw new IllegalArgumentException("Yorunge gerekli.");
        }
        validateShot(paths.shot);
        SimulationMessages.Shot shot = paths.shot;
        if (paths.targetSegments == null || paths.targetSegments.isEmpty()
                || paths.targetSegments.size() > MAX_SEGMENTS || !Double.isFinite(paths.endTime)) {
            throw new IllegalArgumentException("Yorunge parca sayisi veya bitisi gecersiz.");
        }
        double expectedEnd = shot.t0 + exitDuration(shot);
        if (!sameTime(paths.endTime, expectedEnd)) {
            throw new IllegalArgumentException("Yorunge merminin haritadan cikisinda bitmeli.");
        }

        double time = shot.t0;
        double x = shot.tx;
        double y = shot.ty;
        double vx = inwardVelocity(x, shot.tvx, shot.width);
        double vy = inwardVelocity(y, shot.tvy, shot.height);
        for (SimulationMessages.Segment segment : paths.targetSegments) {
            if (segment == null) {
                throw new IllegalArgumentException("Bos yorunge parcasi.");
            }
            requireFinite(segment.start, segment.end, segment.x, segment.y, segment.vx, segment.vy);
            if (!sameTime(segment.start, time) || segment.end < segment.start
                    || segment.end > paths.endTime + timeTolerance(segment.end, paths.endTime)
                    || (segment.end == segment.start && paths.targetSegments.size() != 1)) {
                throw new IllegalArgumentException("Yorunge zamanlari ardisik ve artan olmali.");
            }
            // Paket, atistaki hedef modeliyle ayni hareketi temsil etmeli.
            double precision = POSITION_EPS + Math.max(Math.abs(segment.vx), Math.abs(segment.vy))
                * 2 * timeTolerance(segment.start, segment.end);
            if (Math.abs(segment.x - x) > precision || Math.abs(segment.y - y) > precision
                    || Math.abs(segment.vx - vx) > 1e-7 || Math.abs(segment.vy - vy) > 1e-7) {
                throw new IllegalArgumentException("Yorunge konumu veya hizi surekli degil.");
            }
            double duration = segment.end - segment.start;
            double tx = boundaryTime(segment.x, segment.vx, shot.width);
            double ty = boundaryTime(segment.y, segment.vy, shot.height);
            x = segment.x + segment.vx * duration;
            y = segment.y + segment.vy * duration;
            requireInside(x, y, shot.width, shot.height, precision);
            x = clamp(x, shot.width);
            y = clamp(y, shot.height);
            vx = segment.vx;
            vy = segment.vy;
            double bounceEps = timeTolerance(segment.start, segment.end);
            if (tx <= duration + bounceEps) { x = vx > 0 ? shot.width : 0; vx = -vx; }
            if (ty <= duration + bounceEps) { y = vy > 0 ? shot.height : 0; vy = -vy; }
            time = segment.end;
        }
        if (!sameTime(time, paths.endTime)) {
            throw new IllegalArgumentException("Yorunge tum atis suresini kapsamiyor.");
        }
    }

    private static double exitDuration(SimulationMessages.Shot shot) {
        return Math.min(boundaryTime(shot.bx, shot.bvx, shot.width),
            boundaryTime(shot.by, shot.bvy, shot.height));
    }

    private static double boundaryTime(double position, double velocity, double size) {
        return velocity > 0 ? (size - position) / velocity
            : velocity < 0 ? -position / velocity : Double.POSITIVE_INFINITY;
    }

    private static double inwardVelocity(double position, double velocity, double size) {
        return (position == 0 && velocity < 0) || (position == size && velocity > 0)
            ? -velocity : velocity;
    }

    private static double clamp(double position, double size) {
        return Math.max(0, Math.min(size, position));
    }

    private static boolean sameTime(double first, double second) {
        return Math.abs(first - second) <= timeTolerance(first, second);
    }

    private static double timeTolerance(double first, double second) {
        return 8 * Math.ulp(Math.max(Math.abs(first), Math.abs(second)));
    }

    private static void requireInside(double x, double y, double width, double height, double epsilon) {
        if (x < -epsilon || x > width + epsilon || y < -epsilon || y > height + epsilon) {
            throw new IllegalArgumentException("Nesne merkezi harita disinda.");
        }
    }

    private static void requireFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Yorunge degerleri sonlu olmali.");
            }
        }
    }
}
