import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bu projede gönderilen RPR SpatialFPW (algoritma 2) kaydının ters dönüşümü. */
public final class SpatialDecoder {
    private SpatialDecoder() { }

    public static SpatialState decode(byte[] bytes) {
        // Variant discriminator sonrasında 8-byte hizalama vardır. FPW içinde
        // frozen octet'inden sonra orientation için 4-byte hizalama uygulanır.
        if (bytes.length != 60 || bytes[0] != 2) {
            throw new IllegalArgumentException("60 byte SpatialFPW bekleniyor.");
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        b.position(8);
        double x = b.getDouble(), y = b.getDouble(), z = b.getDouble();
        boolean frozen = b.get() != 0;
        b.position(36);
        float psi = b.getFloat(), theta = b.getFloat(), phi = b.getFloat();
        float vx = b.getFloat(), vy = b.getFloat(), vz = b.getFloat();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(psi) || !Float.isFinite(theta) || !Float.isFinite(phi)
                || !Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(vz)) {
            throw new IllegalArgumentException("Spatial sonlu olmayan sayı içeriyor.");
        }
        return new SpatialState(x, y, z, vx, vy, vz, psi, theta, phi, frozen);
    }
}
