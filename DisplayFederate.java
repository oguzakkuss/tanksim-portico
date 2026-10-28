import hla.rti1516e.*;
import hla.rti1516e.encoding.*;
import hla.rti1516e.exceptions.*;

import java.io.File;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DisplayFederate {

    private static final String FEDERATION = "TankSimulation";

    //kuyrukta taşınacak veri: hangi nesneye ait ve içeriği ne
    private static class IncomingSpatial {
        final ObjectInstanceHandle object;
        final byte[] bytes;

        IncomingSpatial(ObjectInstanceHandle object, byte[] bytes) {
            this.object = object;
            this.bytes = bytes;
        }

    }

    // rti haberleşme metotları
    private static class DisplayAmbassador extends NullFederateAmbassador {

        AttributeHandle spatialHandle;

        final Queue<IncomingSpatial> inbox = new ConcurrentLinkedQueue<>();

        @Override 
        public void discoverObjectInstance(
            ObjectInstanceHandle object,
            ObjectClassHandle objectClass,
            String objectName) {
                System.out.println("nesne kesfedildi: " + objectName);
            }

        @Override
        public void reflectAttributeValues(
            ObjectInstanceHandle object,
            AttributeHandleValueMap attributes,
            byte[] tag,
            OrderType order,
            TransportationTypeHandle transport,
            SupplementalReadInfo info) {
                byte[] bytes = attributes.get(spatialHandle);

                if (bytes != null) {
                    // kopyala ve kuyruğa ekle
                    inbox.add(new IncomingSpatial(object, bytes.clone()));
                }
            }
        }

        public static void main(String[] args) throws Exception {
            RTIambassador rti = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();

            EncoderFactory f = RtiFactoryFactory.getRtiFactory().getEncoderFactory();

            DisplayAmbassador callbacks = new DisplayAmbassador();

            rti.connect(callbacks, CallbackModel.HLA_EVOKED);
            boolean joined = false;

            try {
                File fom = new File("fom/RPR2-D17_2010.xml");

                try{
                    rti.createFederationExecution(FEDERATION, fom.toURI().toURL());
                } catch (FederationExecutionAlreadyExists e) {
                    System.out.println("Federasyon zaten var");
                }

                rti.joinFederationExecution("DisplayFederate", "Display", FEDERATION);

                joined = true;
                ObjectClassHandle tankClass = rti.getObjectClassHandle(
                    "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.GroundVehicle"
                );

                callbacks.spatialHandle = rti.getAttributeHandle(
                    tankClass,
                    "Spatial"
                );

                AttributeHandleSet attributes = rti.getAttributeHandleSetFactory().create();

                attributes.add(callbacks.spatialHandle);

                rti.subscribeObjectClassAttributes(tankClass, attributes);

                System.out.println("Display hazir: Spatial aboneligi yapildi.");

                while (true) {
                    rti.evokeMultipleCallbacks(0.01, 0.1);

                    IncomingSpatial update = callbacks.inbox.poll();

                    if (update != null) {
                        System.out.println("Yeni Spatial verisi geldi: " + update.object.getName());

                        printSpatial(f, update.bytes);
                        break;
                    }
                }
            } finally {
                try {
                    if (joined) {
                        rti.resignFederationExecution(ResignAction.NO_ACTION);

                        try {
                            rti.destroyFederationExecution(FEDERATION);
                        } catch (FederatesCurrentlyJoined | FederationExecutionDoesNotExist e) {
                            System.out.println("Federasyon zaten var");
                        }
                    }
                } finally {
                    rti.disconnect();
                }
            }
        }

        private static void printSpatial(EncoderFactory f, byte[] bytes) throws DecoderException{
            
            if(bytes.length != 60 || bytes[0] != 2) {
                throw new IllegalArgumentException("Bu decoder yalnizca 60 byte DRM_FPW Spatial destekliyor.");
            }

            HLAfloat64BE x = f.createHLAfloat64BE();
            HLAfloat64BE y = f.createHLAfloat64BE();
            HLAfloat64BE z = f.createHLAfloat64BE();

            HLAoctet frozen = f.createHLAoctet();

            HLAfloat32BE psi = f.createHLAfloat32BE();
            HLAfloat32BE theta = f.createHLAfloat32BE();
            HLAfloat32BE phi = f.createHLAfloat32BE();

            HLAfloat32BE vx = f.createHLAfloat32BE();
            HLAfloat32BE vy = f.createHLAfloat32BE();
            HLAfloat32BE vz = f.createHLAfloat32BE();

            // encoder sırası doğru olmalı
            HLAfixedRecord location = record(f, x, y, z);
            HLAfixedRecord orientation = record(f, psi, theta, phi);
            HLAfixedRecord velocity = record(f, vx, vy, vz);

            HLAfixedRecord fpw = record(f, location, frozen, orientation, velocity);

            HLAvariantRecord<HLAoctet> variant = f.createHLAvariantRecord(f.createHLAoctet((byte) 2));

            variant.setVariant(f.createHLAoctet((byte) 2), fpw);

            HLAfixedRecord spatial = record(f, variant);

            spatial.decode(bytes);

            System.out.printf(
                Locale.ROOT, "Konum: %.1f, %.1f, %.1f%n",
                x.getValue(), y.getValue(), z.getValue()
            );

            System.out.printf(
            Locale.ROOT, "Hiz: %.1f, %.1f, %.1f%n",
            vx.getValue(), vy.getValue(), vz.getValue()
            );

            System.out.printf(
                Locale.ROOT, "Yonelim (radyan): %.4f, %.4f, %.4f%n",
                psi.getValue(), theta.getValue(), phi.getValue()
            );




        }

        private static HLAfixedRecord record(
                EncoderFactory f, DataElement... fields) {

            HLAfixedRecord result = f.createHLAfixedRecord();

            for (DataElement field : fields) {
                result.add(field);
            }

            return result;
        }
    }
    
