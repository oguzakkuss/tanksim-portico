import hla.rti1516e.AttributeHandle;
import hla.rti1516e.AttributeHandleSet;
import hla.rti1516e.AttributeHandleValueMap;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.RTIambassador;

import hla.rti1516e.encoding.DecoderException;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.encoding.HLAfloat64BE;
import hla.rti1516e.encoding.HLAinteger32BE;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

public final class SimulationStateCodec {

    public final ObjectClassHandle objectClass;

    public final AttributeHandle runId;
    public final AttributeHandle simTime;
    public final AttributeHandle phase;
    public final AttributeHandle mapWidth;
    public final AttributeHandle mapHeight;
    public final AttributeHandle stepSeconds;

    public final AttributeHandle targetEntityId;
    public final AttributeHandle playerEntityId;

    public SimulationStateCodec(RTIambassador rti) throws Exception {
        objectClass = rti.getObjectClassHandle(
            "HLAobjectRoot.SimulationState"
        );

        runId = rti.getAttributeHandle(objectClass, "RunId");
        simTime = rti.getAttributeHandle(objectClass, "SimTime");
        phase = rti.getAttributeHandle(objectClass, "Phase");
        mapWidth = rti.getAttributeHandle(objectClass, "MapWidth");
        mapHeight = rti.getAttributeHandle(objectClass, "MapHeight");
        stepSeconds = rti.getAttributeHandle(objectClass, "StepSeconds");
        targetEntityId = rti.getAttributeHandle(objectClass, "TargetEntityId");
        playerEntityId = rti.getAttributeHandle(objectClass, "PlayerEntityId");
    }

    public AttributeHandleSet attributes(RTIambassador rti)
            throws Exception {

        AttributeHandleSet result =
            rti.getAttributeHandleSetFactory().create();

        result.add(runId);
        result.add(simTime);
        result.add(phase);
        result.add(mapWidth);
        result.add(mapHeight);
        result.add(stepSeconds);

        result.add(targetEntityId);
        result.add(playerEntityId);

        return result;
    }

    // RTI thread'inde çağrılır.
    public AttributeHandleValueMap encode(
            RTIambassador rti,
            EncoderFactory f,
            SimulationStateData data) throws Exception {

        if (data == null || !data.isComplete()) {
            throw new IllegalArgumentException(
                "Yayin icin tam SimulationState gerekli."
            );
        }

        AttributeHandleValueMap result =
            rti.getAttributeHandleValueMapFactory().create(8);

        result.put(
            runId,
            encodeUnicodeString(data.runId)
        );

        result.put(
            simTime,
            f.createHLAfloat64BE(data.simTime).toByteArray()
        );

        result.put(
            phase,
            f.createHLAinteger32BE(data.phase.code).toByteArray()
        );

        result.put(
            mapWidth,
            f.createHLAfloat64BE(data.mapWidth).toByteArray()
        );

        result.put(
            mapHeight,
            f.createHLAfloat64BE(data.mapHeight).toByteArray()
        );

        result.put(
            stepSeconds,
            f.createHLAfloat64BE(data.stepSeconds).toByteArray()
        );

        result.put(
            targetEntityId,
            EntityIdCodec.encode(f, data.targetEntityId)
        );

        result.put(
            playerEntityId,
            EntityIdCodec.encode(f, data.playerEntityId)
        );

        return result;
    }

    // Worker yalnızca kopyalanmış byte'ları çözer.
    public SimulationStateData decode(
            EncoderFactory f,
            Map<AttributeHandle, byte[]> values)
            throws DecoderException {

        String decodedRunId = null;

        if (values.containsKey(runId)) {
            decodedRunId = decodeUnicodeString(values.get(runId));
        }

        SimulationStateData.Phase decodedPhase = null;

        if (values.containsKey(phase)) {
            HLAinteger32BE field = f.createHLAinteger32BE();
            field.decode(values.get(phase));

            decodedPhase =
                SimulationStateData.Phase.fromCode(field.getValue());
        }

        return new SimulationStateData(
            decodedRunId,
            decodeDouble(f, values, simTime),
            decodedPhase,
            decodeDouble(f, values, mapWidth),
            decodeDouble(f, values, mapHeight),
            decodeDouble(f, values, stepSeconds),

            values.containsKey(targetEntityId)
                ? EntityIdCodec.decode(f, values.get(targetEntityId))
                : null,

            values.containsKey(playerEntityId)
                ? EntityIdCodec.decode(f, values.get(playerEntityId))
                : null
        );
    }

    private static Double decodeDouble(
            EncoderFactory f,
            Map<AttributeHandle, byte[]> values,
            AttributeHandle handle) throws DecoderException {

        if (!values.containsKey(handle)) {
            return null;
        }

        HLAfloat64BE field = f.createHLAfloat64BE();
        field.decode(values.get(handle));

        return field.getValue();
    }

    // HLAunicodeString:
    // 4 byte BE karakter sayısı + her karakter için 2 byte BE.
    static byte[] encodeUnicodeString(String value) {
        int length = Math.addExact(
            4,
            Math.multiplyExact(value.length(), 2)
        );

        ByteBuffer buffer = ByteBuffer
            .allocate(length)
            .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(value.length());

        for (int i = 0; i < value.length(); i++) {
            buffer.putChar(value.charAt(i));
        }

        return buffer.array();
    }

    static String decodeUnicodeString(byte[] bytes)
            throws DecoderException {

        if (bytes.length < 4) {
            throw new DecoderException(
                "HLAunicodeString uzunluk bilgisi eksik."
            );
        }

        ByteBuffer buffer = ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.BIG_ENDIAN);

        int count = buffer.getInt();

        if (count < 0 || 2L * count != buffer.remaining()) {
            throw new DecoderException(
                "HLAunicodeString uzunlugu gecersiz."
            );
        }

        char[] chars = new char[count];

        for (int i = 0; i < count; i++) {
            chars[i] = buffer.getChar();
        }

        return new String(chars);
    }
}