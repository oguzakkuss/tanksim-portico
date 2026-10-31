import hla.rti1516e.encoding.DecoderException;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.encoding.HLAfixedRecord;
import hla.rti1516e.encoding.HLAinteger16BE;

public final class EntityIdCodec {

    private EntityIdCodec() {
    }

    public static byte[] encode(EncoderFactory f, EntityId id) {
        HLAfixedRecord federate = f.createHLAfixedRecord();

        federate.add(
            f.createHLAinteger16BE((short) id.siteId)
        );

        federate.add(
            f.createHLAinteger16BE((short) id.applicationId)
        );

        HLAfixedRecord entity = f.createHLAfixedRecord();

        entity.add(federate);
        entity.add(
            f.createHLAinteger16BE((short) id.entityNumber)
        );

        return entity.toByteArray();
    }

    public static EntityId decode(
            EncoderFactory f,
            byte[] bytes) throws DecoderException {

        if (bytes == null || bytes.length != 6) {
            throw new DecoderException(
                "EntityIdentifierStruct 6 byte olmali."
            );
        }

        HLAinteger16BE site = f.createHLAinteger16BE();
        HLAinteger16BE application = f.createHLAinteger16BE();
        HLAinteger16BE number = f.createHLAinteger16BE();

        HLAfixedRecord federate = f.createHLAfixedRecord();
        federate.add(site);
        federate.add(application);

        HLAfixedRecord entity = f.createHLAfixedRecord();
        entity.add(federate);
        entity.add(number);

        entity.decode(bytes);

        return new EntityId(
            Short.toUnsignedInt(site.getValue()),
            Short.toUnsignedInt(application.getValue()),
            Short.toUnsignedInt(number.getValue())
        );
    }
}