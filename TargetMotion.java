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
        if (width <= 0.0 || height <= 0.0) {
            throw new IllegalArgumentException("Genislik ve yukseklik pozitif olmalidir.");
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        double length = Math.hypot(directionX, directionY);

        if (length == 0.0) {
            throw new IllegalArgumentException("Yön vektörü sıfır olamaz.");
        }

        double unitX = directionX / length;
        double unitY = directionY / length;

        this.vx = unitX * TARGET_SPEED;
        this.vy = unitY * TARGET_SPEED;
    }

    public void update(double dt) {
        x += vx * dt;
        y += vy * dt;

        while (x < 0.0 || x > width) {
            if (x < 0.0) {
                x = -x;
            } else {
                x = width - (x - width);
            }
            vx = -vx;
        }

        if((x == 0.0 && vx < 0.0) || (x == width && vx > 0.0)) {
            vx = -vx;
        }

        while (y < 0.0 || y > height) {
            if (y < 0.0) {
                y = -y;
            } else {
                y = height - (y - height);
            }
            vy = -vy;
        }

        if((y == 0.0 && vy < 0.0) || (y == height && vy > 0.0)) {
            vy = -vy;
        }
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }
}

