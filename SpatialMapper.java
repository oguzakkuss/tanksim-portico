public final class SpatialMapper {
    private static final double ORIGIN_X = 6378137.0;

    private SpatialMapper() {
    }

    public static SpatialState fromTarget(TargetMotion target) {
         return fromLocal(
            target.getX(),
            target.getY(),
            target.getVx(),
            target.getVy(),
            target.getVx(),
            target.getVy()
        );
    }

        public static SpatialState fromLocal(
            double x,
            double y,
            double vx,
            double vy,
            double directionX,
            double directionY) {

        double length = Math.hypot(directionX, directionY);

        if (!Double.isFinite(length) || length == 0.0) {
            throw new IllegalArgumentException(
                "Yon sonlu ve sifirdan farkli olmali."
            );
        }

        float psi = (float) (
            directionX >= 0.0 ? Math.PI / 2 : -Math.PI / 2
        );

        float theta = (float) Math.atan2(
            -directionY,
            Math.abs(directionX)
        );

        float phi = -psi;

        return new SpatialState(
            ORIGIN_X, x, y,
            0.0f, (float) vx, (float) vy,
            psi, theta, phi,
            false
        );
    }


}