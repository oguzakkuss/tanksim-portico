import hla.rti1516e.*;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.exceptions.*;
import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/**
 * Dört federenin ortak bağlantı ve callback altyapısı.
 * RTI thread'i: bütün RTI servisleri + evokeMultipleCallbacks + callback'ler.
 * Tek worker: kopyalanmış byte'ların çözülmesi ve isteğe bağlı saf hesaplar.
 * Ana thread: poll() ile sonuçları alır; kendi modelinin tek yazarıdır.
 * Ana thread'in send/publish çağrıları RTI'ye doğrudan girmez, iş kuyruğuna ekler.
 */
public final class FederateRuntime implements AutoCloseable {
    public enum Kind { DISCOVERED, ENTITY, STATE, REMOVED, MESSAGE }

    public static final class Event {
        public final Kind kind;
        public final String objectKey, objectType;
        public final EntityId entityId;
        public final SpatialState spatial;
        public final SimulationStateData state;
        public final Object message;
        Event(Kind kind, String key, String type, EntityId id, SpatialState spatial,
              SimulationStateData state, Object message) {
            this.kind = kind; this.objectKey = key; this.objectType = type;
            this.entityId = id; this.spatial = spatial; this.state = state; this.message = message;
        }
    }

    @FunctionalInterface private interface RtiWork { void run() throws Exception; }
    private final String role;
    private final boolean owner;
    private final Queue<Event> results = new ConcurrentLinkedQueue<>();
    private final Queue<Exception> failures = new ConcurrentLinkedQueue<>();
    private final Queue<RtiWork> outbound = new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor worker;
    private final Thread rtiThread;
    private volatile boolean running = true, ready;

    // Aşağıdaki RTI nesneleri ve cache yalnız rtiThread tarafından kullanılır.
    private RTIambassador rti;
    private EncoderFactory encoder, decoder;
    private SimulationStateCodec stateCodec;
    private MessageCodec messages;
    private Object configuredModel;
    private ObjectClassHandle tankClass, bulletClass;
    private AttributeHandle spatialHandle, idHandle;
    private AttributeHandleSet entityAttributes;
    private ObjectInstanceHandle stateObject;
    private final Map<EntityId, ObjectInstanceHandle> ownedEntities = new HashMap<>();
    private final Map<ObjectInstanceHandle, AttributeHandleValueMap> cache = new HashMap<>();
    private final Map<ObjectInstanceHandle, String> kinds = new HashMap<>();
    private final Queue<ObjectInstanceHandle> initialRequests = new ArrayDeque<>();
    private final Queue<ValueRequest> valueRequests = new ArrayDeque<>();

    public FederateRuntime(String role, boolean owner) {
        this.role = role; this.owner = owner;
        // Sınırsız worker kuyruğu gecikmeyi gizleyip belleği tüketmesin.
        // Dolarsa hatayı ana döngüye taşırız; kritik olayları sessizce düşürmeyiz.
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4096), r -> new Thread(r, role + "-worker"));
        rtiThread = new Thread(this::runRti, role + "-rti");
    }

    public void start() { rtiThread.start(); }
    public boolean isReady() { return ready; }
    public boolean isAlive() { return rtiThread.isAlive(); }
    public Event poll() { return results.poll(); }
    public void checkFailure() throws Exception {
        Exception e = failures.poll();
        if (e != null) throw e;
    }

    /** Ağdan gelmeyen saf hesap da aynı worker/sonuç kuyruğu yolunu kullanır. */
    public void compute(Callable<Object> calculation) {
        submit(() -> {
            Object result = calculation.call();
            results.add(new Event(Kind.MESSAGE, null, null, null, null, null, result));
            return null;
        });
    }

    public void send(Object message) {
        outbound.add(() -> {
            MessageCodec.Encoded encoded = messages.encode(message);
            rti.sendInteraction(encoded.interaction, encoded.parameters, new byte[0]);
        });
    }

    public void publishState(SimulationStateData state) {
        requireOwner();
        outbound.add(() -> {
            if (stateObject == null) stateObject = rti.registerObjectInstance(stateCodec.objectClass);
            update(stateObject, stateCodec.encode(rti, encoder, state));
        });
    }

    public void publishEntity(EntityId id, String type, SpatialState state) {
        requireOwner();
        outbound.add(() -> {
            ObjectInstanceHandle object = ownedEntities.get(id);
            if (object == null) {
                object = rti.registerObjectInstance("BULLET".equals(type) ? bulletClass : tankClass);
                ownedEntities.put(id, object);
            }
            AttributeHandleValueMap values = rti.getAttributeHandleValueMapFactory().create(2);
            values.put(idHandle, EntityIdCodec.encode(encoder, id));
            values.put(spatialHandle, SpatialEncoder.encodeFpw(encoder, state.x, state.y, state.z,
                    state.vx, state.vy, state.vz, state.psi, state.theta, state.phi));
            update(object, values);
        });
    }

    public void deleteEntity(EntityId id) {
        requireOwner();
        outbound.add(() -> {
            ObjectInstanceHandle object = ownedEntities.remove(id);
            if (object != null) {
                rti.deleteObjectInstance(object, new byte[0]);
                cache.remove(object);
            }
        });
    }

    private void requireOwner() {
        if (!owner) throw new IllegalStateException("Gerçek nesnelerin sahibi yalnız TankFederate.");
    }

    private void submit(Callable<Void> work) {
        try {
            worker.execute(() -> {
                try { work.call(); } catch (Exception e) { failures.add(e); }
            });
        } catch (RejectedExecutionException e) {
            if (running) failures.add(e);
        }
    }

    private void update(ObjectInstanceHandle object, AttributeHandleValueMap all) throws Exception {
        AttributeHandleValueMap old = cache.get(object);
        AttributeHandleValueMap changed = rti.getAttributeHandleValueMapFactory().create(all.size());
        for (Map.Entry<AttributeHandle, byte[]> entry : all.entrySet()) {
            if (old == null || !Arrays.equals(old.get(entry.getKey()), entry.getValue()))
                changed.put(entry.getKey(), entry.getValue());
        }
        if (!changed.isEmpty()) rti.updateAttributeValues(object, changed, new byte[0]);
        // RTI geçmiş güncellemeleri tekrar oynatmaz. Geç katılan Display'in
        // requestAttributeValueUpdate isteğine bu tam snapshot ile yanıt verilir.
        cache.put(object, all);
    }

    private void runRti() {
        boolean connected = false, joined = false;
        try {
            // Portico 2.1.4'te erken RoleCall, MOM hazırlanırken NPE üretebiliyor.
            // Bu proje MOM yönetim nesnelerini kullanmıyor. Yalnız bu JVM için
            // kapatıyoruz; istenirse -Dportico.mom=enabled ile açılabilir.
            if (System.getProperty("portico.mom") == null)
                System.setProperty("portico.mom", "disabled");
            RtiFactory factory = RtiFactoryFactory.getRtiFactory();
            rti = factory.getRtiAmbassador();
            encoder = factory.getEncoderFactory();
            decoder = factory.getEncoderFactory();
            rti.connect(new Ambassador(), CallbackModel.HLA_EVOKED);
            connected = true;
            URL[] fom = {new File("fom/RPR2-D17_2010.xml").toURI().toURL(),
                         new File("fom/TankSim.xml").toURI().toURL()};
            if (owner) {
                try { rti.createFederationExecution(SimulationConfig.FEDERATION, fom); }
                catch (FederationExecutionAlreadyExists expected) { /* Var olan federasyona katıl. */ }
            }
            // Federasyonu Tank oluşturur. Diğerleri FOM'u yeniden create etmeye
            // çalışmadan katılır; erken başlatıldılarsa Tank için kısa süre bekler.
            long joinStarted = System.nanoTime();
            while (running) {
                try {
                    rti.joinFederationExecution(role + "Federate", role, SimulationConfig.FEDERATION);
                    joined = true;
                    break;
                } catch (FederationExecutionDoesNotExist waitingForTank) {
                    if ((System.nanoTime() - joinStarted) / 1e9 > SimulationConfig.STARTUP_TIMEOUT_SECONDS)
                        throw waitingForTank;
                    Thread.sleep(100);
                }
            }
            if (!running) return;
            System.out.println(role + " RTI_JOINED");
            awaitParticipants();
            if (!running) return;
            configureDeclarations();
            ready = true;
            System.out.println(role + " RTI_READY");
            while (running || !outbound.isEmpty()) {
                refreshModelIfChanged();
                // Kısa callback penceresi outbound komutlarını ve kapanışı geciktirmez.
                rti.evokeMultipleCallbacks(0.001, 0.01);
                refreshModelIfChanged();
                for (int n = 0; n < 256; n++) {
                    RtiWork work = outbound.poll();
                    if (work == null) break;
                    try { work.run(); }
                    catch (InteractionClassNotPublished | ObjectClassNotPublished changedDuringSend) {
                        // Model kontrolü ile servis çağrısı arasında manifest geldiyse
                        // bir kez yeniden ilan edip henüz gönderilmemiş işi tekrar dene.
                        configureDeclarations();
                        work.run();
                    }
                }
                requestInitialValues();
                answerValueRequests();
            }
        } catch (Exception e) {
            failures.add(e);
        } finally {
            ready = false;
            if (joined) {
                try { rti.resignFederationExecution(owner ? ResignAction.DELETE_OBJECTS : ResignAction.NO_ACTION); }
                catch (Exception e) { failures.add(e); }
                try { rti.destroyFederationExecution(SimulationConfig.FEDERATION); }
                catch (FederatesCurrentlyJoined | FederationExecutionDoesNotExist expected) { }
                catch (Exception e) { failures.add(e); }
            }
            if (connected) {
                try { rti.disconnect(); } catch (Exception e) { failures.add(e); }
            }
            System.out.println(role + " RTI_CLOSED");
        }
    }

    private void refreshModelIfChanged() throws Exception {
        if (configuredModel != PorticoCompatibility.modelToken(rti)) {
            configureDeclarations();
            // Yeniden abonelikten önce kaçmış olabilecek sabit alanları da al.
            // Yalnız sonraki SimTime/Spatial güncellemesini beklemek yeterli değil.
            initialRequests.addAll(kinds.keySet());
            System.out.println(role + " FOM_DECLARATIONS_REFRESHED");
        }
    }

    private void configureDeclarations() throws Exception {
        configuredModel = PorticoCompatibility.modelToken(rti);
        stateCodec = new SimulationStateCodec(rti);
        messages = new MessageCodec(rti, role);
        tankClass = rti.getObjectClassHandle("HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.GroundVehicle");
        bulletClass = rti.getObjectClassHandle("HLAobjectRoot.BaseEntity.PhysicalEntity.Munition");
        spatialHandle = rti.getAttributeHandle(tankClass, "Spatial");
        idHandle = rti.getAttributeHandle(tankClass, "EntityIdentifier");
        entityAttributes = rti.getAttributeHandleSetFactory().create();
        entityAttributes.add(spatialHandle); entityAttributes.add(idHandle);
        if (owner) {
            rti.publishObjectClassAttributes(stateCodec.objectClass, stateCodec.attributes(rti));
            rti.publishObjectClassAttributes(tankClass, entityAttributes);
            rti.publishObjectClassAttributes(bulletClass, entityAttributes);
        } else {
            rti.subscribeObjectClassAttributes(stateCodec.objectClass, stateCodec.attributes(rti));
            if ("Display".equals(role)) {
                rti.subscribeObjectClassAttributes(tankClass, entityAttributes);
                rti.subscribeObjectClassAttributes(bulletClass, entityAttributes);
            }
        }
    }

    private void awaitParticipants() throws Exception {
        // Portico 2.1.4 yeni üye katılınca FOM metadata'sını yeniler. Interaction
        // yayınlarını üye katılımları tamamlanmadan açmak, RTI'nin eski metadata
        // anahtarlarını tutup NotPublished üretmesine yol açabiliyor. Dört sabit
        // katılımcı join olduktan sonra pub/sub kurulur; bu yalnız başlangıç
        // bariyeridir, HLA Time Management veya simülasyon adımı değildir.
        long started = System.nanoTime();
        while (running) {
            if (PorticoCompatibility.allParticipantsJoined(rti)) return;
            if ((System.nanoTime() - started) / 1e9 > SimulationConfig.STARTUP_TIMEOUT_SECONDS)
                throw new IllegalStateException("Dört federe federasyona katılmadı (join zaman aşımı).");
            rti.evokeMultipleCallbacks(0.001, 0.01);
            Thread.sleep(50);
        }
    }

    private void requestInitialValues() throws Exception {
        ObjectInstanceHandle object;
        while ((object = initialRequests.poll()) != null) {
            String kind = kinds.get(object);
            if (kind == null) continue;
            try {
                rti.requestAttributeValueUpdate(object,
                    "STATE".equals(kind) ? stateCodec.attributes(rti) : entityAttributes, new byte[0]);
            } catch (ObjectInstanceNotKnown expected) { /* İstekten önce silinmiş olabilir. */ }
        }
    }

    private void answerValueRequests() throws Exception {
        // İlk publish henüz kuyruğa gelmediyse isteği sonraki turda tekrar dene.
        int count = valueRequests.size();
        for (int i = 0; i < count; i++) {
            ValueRequest request = valueRequests.remove();
            AttributeHandleValueMap all = cache.get(request.object);
            if (all == null) {
                if (request.object.equals(stateObject) || ownedEntities.containsValue(request.object))
                    valueRequests.add(request);
                continue;
            }
            AttributeHandleValueMap selected = rti.getAttributeHandleValueMapFactory().create(all.size());
            for (AttributeHandle attribute : request.attributes)
                if (all.containsKey(attribute)) selected.put(attribute, all.get(attribute));
            if (!selected.isEmpty()) rti.updateAttributeValues(request.object, selected, request.tag);
        }
    }

    private static final class ValueRequest {
        final ObjectInstanceHandle object;
        final Set<AttributeHandle> attributes;
        final byte[] tag;
        ValueRequest(ObjectInstanceHandle object, AttributeHandleSet attributes, byte[] tag) {
            this.object = object; this.attributes = new HashSet<>(attributes);
            this.tag = tag == null ? new byte[0] : tag.clone();
        }
    }

    private final class Ambassador extends NullFederateAmbassador {
        @Override public void connectionLost(String reason) {
            failures.add(new IllegalStateException("RTI bağlantısı kesildi: " + reason));
        }
        @Override public void discoverObjectInstance(ObjectInstanceHandle object,
                ObjectClassHandle objectClass, String name) {
            String type = objectClass.equals(stateCodec.objectClass) ? "STATE"
                    : objectClass.equals(tankClass) ? "TANK" : objectClass.equals(bulletClass) ? "BULLET" : null;
            if (type == null) return;
            if (kinds.containsKey(object)) return; // Abonelik yenilemesi tekrar discovery üretebilir.
            kinds.put(object, type);
            submit(() -> { results.add(new Event(Kind.DISCOVERED, object.toString(), type,
                    null, null, null, null)); return null; });
            initialRequests.add(object);
        }
        @Override public void discoverObjectInstance(ObjectInstanceHandle object,
                ObjectClassHandle objectClass, String name, FederateHandle producer) {
            discoverObjectInstance(object, objectClass, name);
        }
        @Override public void reflectAttributeValues(ObjectInstanceHandle object,
                AttributeHandleValueMap values, byte[] tag, OrderType order,
                TransportationTypeHandle transport, SupplementalReflectInfo info) {
            String type = kinds.get(object);
            if (type == null) return;
            Map<AttributeHandle, byte[]> copy = new HashMap<>();
            values.forEach((key, bytes) -> copy.put(key, bytes.clone()));
            SimulationStateCodec capturedCodec = stateCodec;
            AttributeHandle capturedId = idHandle, capturedSpatial = spatialHandle;
            submit(() -> {
                if ("STATE".equals(type)) {
                    results.add(new Event(Kind.STATE, object.toString(), type, null, null,
                            capturedCodec.decode(decoder, copy), null));
                } else {
                    byte[] id = copy.get(capturedId), spatial = copy.get(capturedSpatial);
                    results.add(new Event(Kind.ENTITY, object.toString(), type,
                            id == null ? null : EntityIdCodec.decode(decoder, id),
                            spatial == null ? null : SpatialDecoder.decode(spatial), null, null));
                }
                return null;
            });
        }
        @Override public void receiveInteraction(InteractionClassHandle kind,
                ParameterHandleValueMap values, byte[] tag, OrderType order,
                TransportationTypeHandle transport, SupplementalReceiveInfo info) {
            if (!messages.knows(kind)) return;
            Map<ParameterHandle, byte[]> copy = new HashMap<>();
            values.forEach((key, bytes) -> copy.put(key, bytes.clone()));
            MessageCodec capturedCodec = messages;
            submit(() -> { results.add(new Event(Kind.MESSAGE, null, null, null, null, null,
                    capturedCodec.decode(kind, copy))); return null; });
        }
        @Override public void removeObjectInstance(ObjectInstanceHandle object, byte[] tag,
                OrderType order, SupplementalRemoveInfo info) {
            String type = kinds.remove(object);
            if (type != null) submit(() -> { results.add(new Event(Kind.REMOVED,
                    object.toString(), type, null, null, null, null)); return null; });
        }
        @Override public void provideAttributeValueUpdate(ObjectInstanceHandle object,
                AttributeHandleSet attributes, byte[] tag) {
            valueRequests.add(new ValueRequest(object, attributes, tag));
        }
    }

    @Override public void close() throws Exception {
        running = false;
        rtiThread.join((long) (SimulationConfig.SHUTDOWN_TIMEOUT_SECONDS * 1000));
        worker.shutdownNow();
        if (!worker.awaitTermination(2, TimeUnit.SECONDS))
            failures.add(new IllegalStateException(role + " worker kapanamadı."));
        if (rtiThread.isAlive()) throw new IllegalStateException(role + " RTI kapanış süresi aşıldı.");
        checkFailure();
    }
}
