/** Uygulamanin ortak ayarlari; tum uzunluklar metre, sureler saniyedir. */
public final class SimulationConfig {
    private SimulationConfig() { }

    public static final String FEDERATION =
        System.getProperty("tanksim.federation", "TankSimulation");

    // Ornek: java -Dtanksim.width=150 -Dtanksim.stepSeconds=0.05 TankFederate
    public static final double WIDTH = positive("tanksim.width", 100.0);
    public static final double HEIGHT = positive("tanksim.height", 100.0);
    public static final double STEP_SECONDS = positive("tanksim.stepSeconds", 0.05);
    public static final double TARGET_SPEED = 10.0;
    public static final double PLAYER_SPEED = 5.0;
    public static final double BULLET_SPEED = 50.0;
    public static final double TARGET_RADIUS = positive("tanksim.targetRadius", 2.0);
    public static final double BULLET_RADIUS = positive("tanksim.bulletRadius", 0.2);

    // Konum ve yonler deney senaryolarinda -D ile degistirilebilir.
    public static final double TARGET_X = number("tanksim.target.x", 98.0);
    public static final double TARGET_Y = number("tanksim.target.y", 50.0);
    public static final double TARGET_DIR_X = number("tanksim.target.dirX", 3.0);
    public static final double TARGET_DIR_Y = number("tanksim.target.dirY", 4.0);
    public static final double PLAYER_X = number("tanksim.player.x", 20.0);
    public static final double PLAYER_Y = number("tanksim.player.y", 20.0);
    public static final double PLAYER_DIR_X = number("tanksim.player.dirX", 1.0);
    public static final double PLAYER_DIR_Y = number("tanksim.player.dirY", 0.0);

    // Bunlar sim saati degil, kilitlenmeyi onleyen duvar-saati sinirlaridir.
    public static final double STARTUP_TIMEOUT_SECONDS =
        positive("tanksim.startupTimeoutSeconds", 30.0);
    public static final double PREDICTION_TIMEOUT_SECONDS =
        positive("tanksim.predictionTimeoutSeconds", 10.0);
    public static final double SHUTDOWN_TIMEOUT_SECONDS =
        positive("tanksim.shutdownTimeoutSeconds", 5.0);

    static {
        if (FEDERATION.trim().isEmpty()) {
            throw new IllegalArgumentException("Federasyon adi bos olamaz.");
        }
        if (WIDTH > 1_000_000.0 || HEIGHT > 1_000_000.0
                || TARGET_RADIUS > 1_000_000.0 || BULLET_RADIUS > 1_000_000.0) {
            throw new IllegalArgumentException("Harita veya yaricap cok buyuk.");
        }
        if (TARGET_X < 0 || TARGET_X > WIDTH || TARGET_Y < 0 || TARGET_Y > HEIGHT
                || PLAYER_X < 0 || PLAYER_X > WIDTH || PLAYER_Y < 0 || PLAYER_Y > HEIGHT) {
            throw new IllegalArgumentException("Baslangic konumlari haritada olmali.");
        }
        if ((TARGET_DIR_X == 0 && TARGET_DIR_Y == 0)
                || (PLAYER_DIR_X == 0 && PLAYER_DIR_Y == 0)) {
            throw new IllegalArgumentException("Baslangic yonu sifir olamaz.");
        }
    }

    private static double positive(String property, double fallback) {
        double value = number(property, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException(property + " pozitif olmali.");
        }
        return value;
    }

    private static double number(String property, double fallback) {
        String raw = System.getProperty(property);
        double value = raw == null ? fallback : Double.parseDouble(raw);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(property + " sonlu bir sayi olmali.");
        }
        return value;
    }
}
