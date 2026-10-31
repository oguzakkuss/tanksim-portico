/** Oyuncunun merkez konumu, hizi ve dururken de korunan bakis yonu. */
public final class PlayerMotion {
    private double x;
    private double y;
    private double vx;
    private double vy;
    private double directionX;
    private double directionY;
    private final double width;
    private final double height;

    public PlayerMotion(double x, double y, double dirX, double dirY,
                        double width, double height) {
        requireFinite(x, y, width, height);
        if (width <= 0 || height <= 0 || x < 0 || x > width || y < 0 || y > height) {
            throw new IllegalArgumentException("Oyuncu konumu ve harita boyutlari gecersiz.");
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        steer(dirX, dirY);
    }

    public void steer(double dirX, double dirY) {
        requireFinite(dirX, dirY);
        double scale = Math.max(Math.abs(dirX), Math.abs(dirY));
        if (scale == 0) {
            throw new IllegalArgumentException("Yon sifir olamaz; durmak icin stop kullanilir.");
        }
        // Once olcekleme, cok buyuk ama sonlu girdilerde tasma olmasini onler.
        double sx = dirX / scale;
        double sy = dirY / scale;
        double length = Math.hypot(sx, sy);
        directionX = sx / length;
        directionY = sy / length;
        vx = directionX * SimulationConfig.PLAYER_SPEED;
        vy = directionY * SimulationConfig.PLAYER_SPEED;
    }

    public void stop() {
        vx = 0;
        vy = 0;
        // Bakis yonu degismez: duran tank da ayni yone ates edebilir.
    }

    public void update(double dt) {
        requireFinite(dt);
        if (dt < 0) {
            throw new IllegalArgumentException("Zaman adimi negatif olamaz.");
        }
        if (dt == 0 || (vx == 0 && vy == 0)) {
            return;
        }

        double tx = vx > 0 ? (width - x) / vx
            : vx < 0 ? -x / vx : Double.POSITIVE_INFINITY;
        double ty = vy > 0 ? (height - y) / vy
            : vy < 0 ? -y / vy : Double.POSITIVE_INFINITY;
        double contact = Math.min(tx, ty);
        double elapsed = Math.min(dt, contact);
        x = clamp(x + vx * elapsed, width);
        y = clamp(y + vy * elapsed, height);

        // Bir eksen sinira degdiginde tum hareket durur; duvar boyunca kaymaz.
        if (contact <= dt) {
            stop();
        }
    }

    private static double clamp(double value, double limit) {
        return Math.max(0, Math.min(limit, value));
    }

    private static void requireFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Hareket degerleri sonlu olmali.");
            }
        }
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getVx() { return vx; }
    public double getVy() { return vy; }
    public double getDirectionX() { return directionX; }
    public double getDirectionY() { return directionY; }
}
