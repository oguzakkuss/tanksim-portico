import hla.rti1516e.RTIambassador;
import hla.rti1516e.NullFederateAmbassador;
import hla.rti1516e.CallbackModel;
import hla.rti1516e.RtiFactoryFactory;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.AttributeHandle;
import hla.rti1516e.ResignAction;
import java.io.File;
import hla.rti1516e.AttributeHandleSet;
import hla.rti1516e.ObjectInstanceHandle;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.AttributeHandleValueMap;
import hla.rti1516e.exceptions.*;

public class RtiConnectionTest {
    public static void main(String[] args) throws Exception {
        RTIambassador rti = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();

        NullFederateAmbassador callbacks = new NullFederateAmbassador();

        rti.connect(callbacks, CallbackModel.HLA_EVOKED);

        File fomFile = new File("fom/RPR2-D17_2010.xml");
        String federationName = "TankSimulation";

        try {
            rti.createFederationExecution(federationName, fomFile.toURI().toURL());
        } catch (FederationExecutionAlreadyExists e) {
            System.out.println("Federasyon zaten var");
        }

        rti.joinFederationExecution("TargetFederate", "TankFederate", federationName);

        ObjectClassHandle tankClass = rti.getObjectClassHandle("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.GroundVehicle");
        AttributeHandle spatialHandle = rti.getAttributeHandle(tankClass, "Spatial");

        AttributeHandleSet publishedAttributes = rti.getAttributeHandleSetFactory().create();

        publishedAttributes.add(spatialHandle);
        rti.publishObjectClassAttributes(tankClass, publishedAttributes);

        System.out.println("Spatial bulundu.");

        ObjectInstanceHandle tankInstance = rti.registerObjectInstance(tankClass, "Tank1");

        EncoderFactory encoderFactory = RtiFactoryFactory.getRtiFactory().getEncoderFactory();

        byte[] spatialBytes = SpatialEncoder.encodeFpw(
            encoderFactory,
            6378137.0, 0.0, 0.0,             // Dünya konumu
            0.0f, 0.0f, 10.0f,               // Dünya eksenlerinde hız
            0.0f, (float) (-Math.PI / 2), 0.0f // Yönelim
        );

        AttributeHandleValueMap values = rti.getAttributeHandleValueMapFactory().create(1);
        values.put(spatialHandle, spatialBytes);

        rti.updateAttributeValues(tankInstance, values, new byte[0]);

        System.out.println("Spatial gonderildi: " + spatialBytes.length + "byte");
        rti.resignFederationExecution(ResignAction.DELETE_OBJECTS);
        
        try {
            rti.destroyFederationExecution(federationName);
        } catch (FederationExecutionDoesNotExist e) {
            System.out.println("Federasyon yok");
        }

        rti.disconnect();

        System.out.println("RTI bağlantisi kesildi!");
    }
}
