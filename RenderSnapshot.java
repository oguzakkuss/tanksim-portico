import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Main thread'den Swing EDT'ye yalnız değişmez görünüm verisi taşınır. */
public final class RenderSnapshot {
    public static final class Entity {
        public final String objectKey, label;
        public final double x, y, vx, vy, headingX, headingY;
        public final boolean player, target, bullet, frozen;

        public Entity(String objectKey, String label, double x, double y,
                double vx, double vy, double headingX, double headingY,
                boolean player, boolean target, boolean bullet, boolean frozen) {
            this.objectKey = objectKey;
            this.label = label;
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.headingX = headingX;
            this.headingY = headingY;
            this.player = player;
            this.target = target;
            this.bullet = bullet;
            this.frozen = frozen;
        }
    }

    public final SimulationStateData state;
    public final List<Entity> entities;
    public final String detail;
    public final boolean connected;
    public final boolean stopping;
    public final boolean restartAvailable;

    public RenderSnapshot(SimulationStateData state, List<Entity> entities,
            String detail, boolean connected, boolean stopping) {
        this(state, entities, detail, connected, stopping, false);
    }

    public RenderSnapshot(SimulationStateData state, List<Entity> entities,
            String detail, boolean connected, boolean stopping, boolean restartAvailable) {
        this.state = state;
        this.entities = Collections.unmodifiableList(new ArrayList<>(entities));
        this.detail = detail;
        this.connected = connected;
        this.stopping = stopping;
        this.restartAvailable = restartAvailable;
    }
}
