public final class SpatialState {
    public final double x, y, z;
    public final float vx, vy, vz;
    public final float psi, theta, phi;
    public final boolean frozen;

    public SpatialState(
            double x, double y, double z,
            float vx, float vy, float vz,
            float psi, float theta, float phi,
            boolean frozen) {

        this.x = x;
        this.y = y;
        this.z = z;

        this.vx = vx;
        this.vy = vy;
        this.vz = vz;

        this.psi = psi;
        this.theta = theta;
        this.phi = phi;

        this.frozen = frozen;
    }
}