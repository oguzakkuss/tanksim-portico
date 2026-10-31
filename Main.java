import java.util.Locale;

public class Main {
    public static void main(String[] args) {
        TargetMotion target = new TargetMotion(
            98.0, 50.0,
            3.0, 4.0,
            100.0, 100.0
        );

        for (int step = 0; step <= 2; step++) {
            SpatialState state = SpatialMapper.fromTarget(target);

            System.out.printf(
                Locale.ROOT,
                "t=%d Yerel=(%.1f, %.1f) "
                    + "Dunya=(%.1f, %.1f, %.1f) "
                    + "Hiz=(%.1f, %.1f, %.1f)%n",
                step,
                target.getX(), target.getY(),
                state.x, state.y, state.z,
                state.vx, state.vy, state.vz
            );

            if (step < 2) {
                target.update(1.0);
            }
        }
    }
}