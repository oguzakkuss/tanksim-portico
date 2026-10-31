import java.util.Objects;

public final class EntityId {

    public final int siteId;
    public final int applicationId;
    public final int entityNumber;

    public EntityId(
            int siteId,
            int applicationId,
            int entityNumber) {

        this.siteId = requireUnsigned16(siteId);
        this.applicationId = requireUnsigned16(applicationId);
        this.entityNumber = requireUnsigned16(entityNumber);
    }

    private static int requireUnsigned16(int value) {
        if (value < 0 || value > 65535) {
            throw new IllegalArgumentException(
                "EntityId alani 0..65535 araliginda olmali: " + value
            );
        }

        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof EntityId)) {
            return false;
        }

        EntityId that = (EntityId) other;

        return siteId == that.siteId
            && applicationId == that.applicationId
            && entityNumber == that.entityNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(siteId, applicationId, entityNumber);
    }

    @Override
    public String toString() {
        return siteId + "." + applicationId + "." + entityNumber;
    }
}