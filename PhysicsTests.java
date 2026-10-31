import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Harici kutuphane gerektirmeyen fizik kontrolleri: java PhysicsTests */
public final class PhysicsTests {
    private static int checks;

    public static void main(String[] args) {
        targetMotion();
        playerMotion();
        bulletMotion();
        movingCollision();
        tangentAndMiss();
        zeroRelativeMotion();
        bounceAndCorner();
        initialContactAndExit();
        roundingRegressions();
        invalidInputs();
        predictionMatchesMotion();
        System.out.println("PhysicsTests: " + checks + " kontrol gecti.");
    }

    private static void targetMotion() {
        TargetMotion target = new TargetMotion(98, 50, 3, 4, 100, 100);
        near(10, Math.hypot(target.getVx(), target.getVy()));
        target.update(1);
        near(96, target.getX()); near(58, target.getY());
        near(-6, target.getVx()); near(8, target.getVy());
        target.update(1);
        near(90, target.getX()); near(66, target.getY());

        TargetMotion endpoint = new TargetMotion(90, 50, 1, 0, 100, 100);
        endpoint.update(1);
        near(100, endpoint.getX()); near(-10, endpoint.getVx());
        endpoint.update(0.1);
        near(99, endpoint.getX());

        TargetMotion many = new TargetMotion(98, 50, 3, 4, 100, 100);
        many.update(1000);
        near(98, many.getX()); near(50, many.getY());
        near(6, many.getVx()); near(8, many.getVy());
    }

    private static void playerMotion() {
        PlayerMotion player = new PlayerMotion(98, 50, 3, 4, 100, 100);
        near(5, Math.hypot(player.getVx(), player.getVy()));
        player.update(1);
        // x sinirina 2/3 saniyede deger; y de sadece o kadar ilerler.
        near(100, player.getX()); near(50 + 8.0 / 3, player.getY());
        near(0, player.getVx()); near(0, player.getVy());
        near(0.6, player.getDirectionX()); near(0.8, player.getDirectionY());
        player.steer(-1, 0);
        player.update(1);
        near(95, player.getX());
        player.stop();
        player.update(100);
        near(95, player.getX()); near(-1, player.getDirectionX());

        PlayerMotion outward = new PlayerMotion(100, 20, 1, 1, 100, 100);
        outward.update(1);
        near(100, outward.getX()); near(20, outward.getY());
        near(0, outward.getVx()); near(0, outward.getVy());
    }

    private static void bulletMotion() {
        BulletMotion bullet = new BulletMotion(10, 20, 30, 40);
        bullet.update(0.1);
        near(13, bullet.getX()); near(24, bullet.getY());
        near(50, Math.hypot(bullet.getVx(), bullet.getVy()));
    }

    private static void movingCollision() {
        SimulationMessages.Shot shot = shot(7, 5, 11, 0, -10, 0, 10, 50, 0, 0.2, 0);
        SimulationMessages.Result result = solve(shot);
        status("HIT", result);
        // Hedef de mermi de hareket eder; iki son konumun karsilastirilmasi bunu kacirir.
        near(7 + 0.1 - 0.2 / Math.sqrt(2600), result.eventTime);

        SimulationMessages.Result tunneling = solve(shot(0, 5, 10, 0, 0,
            0, 10, 50, 0, 0.2, 0.2));
        status("HIT", tunneling);
        near((5 - 0.4) / 50, tunneling.eventTime);
    }

    private static void tangentAndMiss() {
        SimulationMessages.Result tangent = solve(shot(0, 5, 12, 0, 0,
            0, 10, 50, 0, 2, 0));
        status("HIT", tangent); near(0.1, tangent.eventTime);
        SimulationMessages.Result nearMiss = solve(shot(0, 5, 12.0001, 0, 0,
            0, 10, 50, 0, 2, 0));
        status("NO_HIT", nearMiss); near(2, nearMiss.eventTime);
    }

    private static void zeroRelativeMotion() {
        SimulationMessages.Result separated = solve(shot(0, 10, 20, 50, 0,
            20, 20, 50, 0, 2, 0.2));
        status("NO_HIT", separated); near(1.6, separated.eventTime);
        SimulationMessages.Result touching = solve(shot(3, 19, 20, 50, 0,
            20, 20, 50, 0, 2, 0.2));
        status("HIT", touching); near(3, touching.eventTime);
    }

    private static void bounceAndCorner() {
        SimulationMessages.Result bounce = solve(shot(0, 98, 50, 10, 0,
            80, 50, 50, 0, 2, 0.2));
        status("HIT", bounce); near(0.33, bounce.eventTime);

        double speed = 10 / Math.sqrt(2);
        SimulationMessages.Trajectories corner = TrajectoryMath.predict(
            shot(5, 98, 98, speed, speed, 0, 0, 50, 0, 2, 0.2));
        check(corner.targetSegments.size() == 2, "Kose tek sekme aninda iki ekseni cevirmeli.");
        near(5 + 2 / speed, corner.targetSegments.get(0).end);
        near(-speed, corner.targetSegments.get(1).vx);
        near(-speed, corner.targetSegments.get(1).vy);
        TrajectoryMath.collide(corner);

        SimulationMessages.Shot many = new SimulationMessages.Shot("test", 1, new EntityId(1, 1, 3),
            0, 2, 5, 10, 0, 0, 0, 1, 0, 10, 10, 0.1, 0.1);
        SimulationMessages.Trajectories parts = TrajectoryMath.predict(many);
        check(parts.targetSegments.size() == 11, "Uzun ufukta tum sekmeler bulunmali.");
        status("NO_HIT", TrajectoryMath.collide(parts));
    }

    private static void initialContactAndExit() {
        SimulationMessages.Result touching = solve(shot(2, 98, 50, -10, 0,
            100, 50, 50, 0, 2, 0.2));
        status("HIT", touching); near(2, touching.eventTime);
        SimulationMessages.Result exit = solve(shot(2, 50, 50, 0, 10,
            100, 10, 50, 0, 2, 0.2));
        status("NO_HIT", exit); near(2, exit.eventTime);
    }

    private static void invalidInputs() {
        rejects(() -> new TargetMotion(0, 0, Double.NaN, 1, 100, 100));
        rejects(() -> new TargetMotion(101, 0, 1, 0, 100, 100));
        rejects(() -> new TargetMotion(0, 0, 1, 0, 100, 100).update(-1));
        rejects(() -> new PlayerMotion(0, 0, 1, 0, 100, 100).steer(0, 0));
        rejects(() -> new BulletMotion(0, 0, 50, 0).update(Double.NaN));
        rejects(() -> TrajectoryMath.predict(shot(0, 50, 50, 10, 0,
            10, 10, Double.NaN, 0, 2, 0.2)));
        rejects(() -> TrajectoryMath.predict(shot(0, 50, 50, 10, 0,
            10, 10, 0, 0, 2, 0.2)));
        rejects(() -> TrajectoryMath.predict(shot(0, 50, 50, 10, 0,
            10, 10, 1e-8, 0, 2, 0.2)));

        SimulationMessages.Shot valid = shot(0, 50, 50, 10, 0, 0, 10, 50, 0, 2, 0.2);
        List<SimulationMessages.Segment> wrong = new ArrayList<>();
        wrong.add(new SimulationMessages.Segment(0, 2, 50, 50, -10, 0));
        rejects(() -> TrajectoryMath.collide(new SimulationMessages.Trajectories(valid, wrong, 2)));
    }

    private static void roundingRegressions() {
        // Sekme ve mermi cikisi double hassasiyetinde ayni ana denk gelir.
        SimulationMessages.Shot almostSameEnd = shot(100, 99, 50, 6, 8,
            91.66666666666666, 10, 50, 0, 2, 0.2);
        SimulationMessages.Trajectories paths = TrajectoryMath.predict(almostSameEnd);
        check(paths.targetSegments.size() == 1, "Yuvarlama artigi yeni parca acmamali.");
        status("NO_HIT", TrajectoryMath.collide(paths));

        // Sayisal tolerans, kucuk dairelerin yerine buyuk bir temas alani koyamaz.
        status("NO_HIT", solve(shot(0, 5, 10.000001, 0, 0,
            0, 10, 50, 0, 1e-8, 1e-8)));
        status("NO_HIT", solve(shot(0, 0, 5e-10, 0, 0,
            0, 0, 50, 0, 0, 0)));
    }

    private static void predictionMatchesMotion() {
        Random random = new Random(1234567);
        for (int i = 0; i < 200; i++) {
            double tx = 100 * random.nextDouble();
            double ty = 100 * random.nextDouble();
            double angle = 2 * Math.PI * random.nextDouble();
            TargetMotion actual = new TargetMotion(tx, ty, Math.cos(angle), Math.sin(angle), 100, 100);
            double bulletAngle = 2 * Math.PI * random.nextDouble();
            SimulationMessages.Shot shot = shot(100, tx, ty, actual.getVx(), actual.getVy(),
                100 * random.nextDouble(), 100 * random.nextDouble(),
                50 * Math.cos(bulletAngle), 50 * Math.sin(bulletAngle), 2, 0.2);
            SimulationMessages.Trajectories paths = TrajectoryMath.predict(shot);
            double previousTime = shot.t0;
            for (SimulationMessages.Segment segment : paths.targetSegments) {
                double middle = (segment.start + segment.end) / 2;
                actual.update(middle - previousTime);
                near(segment.x + segment.vx * (middle - segment.start), actual.getX());
                near(segment.y + segment.vy * (middle - segment.start), actual.getY());
                previousTime = middle;
            }
            // Uretilen parcalar Collision'in sureklilik kontrollerini de gecmeli.
            TrajectoryMath.collide(paths);
            checks++;
        }
    }

    private static SimulationMessages.Shot shot(double t0, double tx, double ty, double tvx,
            double tvy, double bx, double by, double bvx, double bvy, double targetRadius, double bulletRadius) {
        return new SimulationMessages.Shot("test", 1, new EntityId(1, 1, 3), t0,
            tx, ty, tvx, tvy, bx, by, bvx, bvy, 100, 100, targetRadius, bulletRadius);
    }

    private static SimulationMessages.Result solve(SimulationMessages.Shot shot) {
        return TrajectoryMath.collide(TrajectoryMath.predict(shot));
    }

    private static void status(String expected, SimulationMessages.Result actual) {
        check(expected.equals(actual.status), "Beklenen durum " + expected + ", gelen " + actual.status);
    }

    private static void near(double expected, double actual) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= 1e-6,
            "Beklenen " + expected + ", gelen " + actual);
    }

    private static void rejects(Runnable task) {
        try {
            task.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError("Gecersiz girdinin reddedilmesi bekleniyordu.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
