/** Merminin gercek durumunu TankFederate ilerletir; bu sinif isabet karari vermez. */
public final class BulletMotion {
    private double x;
    private double y;
    private final double vx;
    private final double vy;

    public BulletMotion(double x, double y, double vx, double vy) {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(vx) || !Double.isFinite(vy)
                || !Double.isFinite(Math.hypot(vx, vy)) || (vx == 0 && vy == 0)) {
            throw new IllegalArgumentException("Mermi konumu ve hizi gecersiz.");
        }
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
    }

    public void update(double dt) {
        if (!Double.isFinite(dt) || dt < 0) {
            throw new IllegalArgumentException("Mermi zaman adimi sonlu ve negatif olmayan sayi olmali.");
        }
        double nextX = x + vx * dt;
        double nextY = y + vy * dt;
        if (!Double.isFinite(nextX) || !Double.isFinite(nextY)) {
            throw new IllegalArgumentException("Mermi konumu sayisal siniri asti.");
        }
        x = nextX;
        y = nextY;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getVx() { return vx; }
    public double getVy() { return vy; }
}
