/** Merkez 0..width / 0..height sinirlarindan aynasal olarak seker. */
public class TargetMotion {
    private static final double TARGET_SPEED = 10.0;

    private double x;
    private double y;

    private double vx;
    private double vy;

    private final double width;
    private final double height;

    public TargetMotion(double x, double y,
                        double directionX, double directionY, double width, double height) {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)
                || width <= 0 || height <= 0 || !Double.isFinite(2 * width)
                || !Double.isFinite(2 * height) || x < 0 || x > width || y < 0 || y > height) {
            throw new IllegalArgumentException("Hedef konumu veya harita boyutlari gecersiz.");
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        double scale = Math.max(Math.abs(directionX), Math.abs(directionY));
        if (!Double.isFinite(scale) || scale == 0) {
            throw new IllegalArgumentException("Yon sonlu ve sifirdan farkli olmali.");
        }
        double sx = directionX / scale;
        double sy = directionY / scale;
        double length = Math.hypot(sx, sy);
        double unitX = sx / length;
        double unitY = sy / length;

        this.vx = unitX * TARGET_SPEED;
        this.vy = unitY * TARGET_SPEED;
    }

    public void update(double dt) {
        if (!Double.isFinite(dt) || dt < 0) {
            throw new IllegalArgumentException("Zaman adimi sonlu ve negatif olmayan sayi olmali.");
        }
        double nextX = x + vx * dt;
        double nextY = y + vy * dt;
        if (!Double.isFinite(nextX) || !Double.isFinite(nextY)) {
            throw new IllegalArgumentException("Hedef konumu sayisal siniri asti.");
        }

        // 2*boyut periyotlu katlama, buyuk adimda da tekrarli sekmeleri hesaplar.
        double phaseX = positiveModulo(nextX, 2 * width);
        double phaseY = positiveModulo(nextY, 2 * height);
        if (phaseX == 0) { vx = Math.abs(vx); }
        else if (phaseX == width) { vx = -Math.abs(vx); }
        else if (phaseX > width) { vx = -vx; }
        if (phaseY == 0) { vy = Math.abs(vy); }
        else if (phaseY == height) { vy = -Math.abs(vy); }
        else if (phaseY > height) { vy = -vy; }
        x = phaseX <= width ? phaseX : 2 * width - phaseX;
        y = phaseY <= height ? phaseY : 2 * height - phaseY;
    }

    private static double positiveModulo(double value, double period) {
        double phase = value % period;
        return phase < 0 ? phase + period : phase;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getVx() {
        return vx;
    }

    public double getVy() {
        return vy;
    }

}

