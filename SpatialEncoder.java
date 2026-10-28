import hla.rti1516e.encoding.*;

public final class SpatialEncoder {

    public static byte[] encodeFpw(
        EncoderFactory f,
        double x,
        double y,
        double z,
        float vx,
        float vy,
        float vz,
        float psi,
        float theta,
        float phi) {

            HLAfixedRecord location = f.createHLAfixedRecord();
            location.add(f.createHLAfloat64BE(x));
            location.add(f.createHLAfloat64BE(y));
            location.add(f.createHLAfloat64BE(z));

            HLAfixedRecord velocity = f.createHLAfixedRecord();
            velocity.add(f.createHLAfloat32BE(vx));
            velocity.add(f.createHLAfloat32BE(vy));
            velocity.add(f.createHLAfloat32BE(vz));

            HLAfixedRecord orientation = f.createHLAfixedRecord();
            orientation.add(f.createHLAfloat32BE(psi));
            orientation.add(f.createHLAfloat32BE(theta));
            orientation.add(f.createHLAfloat32BE(phi));

            HLAfixedRecord fpw = f.createHLAfixedRecord();
            fpw.add(location);
            fpw.add(f.createHLAoctet((byte) 0));
            fpw.add(orientation);
            fpw.add(velocity);
            
            HLAvariantRecord<HLAoctet> variant = f.createHLAvariantRecord(f.createHLAoctet((byte) 2));
            variant.setVariant(f.createHLAoctet((byte) 2), fpw);

            HLAfixedRecord spatial = f.createHLAfixedRecord();
            spatial.add(variant);

          
            return spatial.toByteArray();

        }
    }