TANK SİMÜLASYONU — Java 11 / Portico 2.1.4

ÇALIŞTIRMA

PowerShell'de bu klasörden:

    .\run.ps1

Bu komut derler ve Tank, Prediction, CollisionDetection, Display için dört
ayrı Java süreci başlatır. Arayüzde X/Y yön bileşenlerini girip "Yönü uygula"
düğmesine bas. Yön normalize edilir: çapraz giderken de hız 5 birim/s olur.
Ok tuşları da aynı X/Y yön alanlarını değiştirip hemen uygular: ↑ Y'yi artırır,
↓ Y'yi azaltır, → X'i artırır, ← X'i azaltır. Her basış 0,1 adımdır; alanlar
-1 ile 1 arasında kalır. Sayı alanı seçiliyken de oklar tankı kontrol eder.
İki yön bileşeni de sıfır olursa tank durur; sonraki okla yeniden hareket eder.
Tuşu basılı tutmak klavye tekrarlarıyla değeri değiştirir; bırakmak tankı durdurmaz.
"Tankı durdur" hızı sıfırlar, bakış yönünü korur. "ATEŞ ET" düğmesi veya Space,
mevcut bakış yönünde mermi üretir. Her Space basışı tek atıştır; basılı tutmak
otomatik atış üretmez. Sayı alanı veya başka bir düğme seçiliyken de Space
ateş eder; hesap beklenirken düğme gibi devre dışıdır.
"Simülasyonu bitir" koşuyu kapatır. Oyun bitip RTI bağlantısı kapandıktan sonra
"Yeniden başlat" düğmesi açılır. Düğme, önceki dört süreç kapandıktan sonra dört
yeni federeyle yeni oyun açar: süre, tank konumları, mermiler ve atış sayaçları
sıfırlanır. Ok tuşları ve Space yeni pencerede de çalışır. Son ekranı kapatırsan
çalıştırma betiği sona erer. Yeniden başlatma, run.ps1 ile açılan oyunlarda aktiftir;
federeleri elle ayrı ayrı açtıysan onları yine elle yeniden başlatmalısın.

Arayüzsüz, bir atış yapıp biten demo:

    .\run.ps1 -Headless -Demo

Yalnız derleme ve yerel kontroller:

    .\build.ps1 -Test

Gerçek Portico ile dört ayrı JVM kullanan entegrasyon testleri:

    .\test.ps1 -Integration

Gerçek başlatıcı ve Swing düğmesiyle iki ardışık oyun / yeniden başlatma testi:

    .\test.ps1 -Restart

Bu test, pencereleri gizleyip ilk oyunda yeniden başlat düğmesini çalıştırır,
ikinci oyunda pencereyi kapatır. Yeni RunId, sıfır saat/atış sayacı ve eski
süreçlerin kapanmasını kontrol eder. Test kayıtları build/restart-tests/ altındadır.

Betikler JAVA_HOME ve RTI_HOME'u kullanır. RTI_HOME boşsa bu makinenin kurulu
C:\Program Files\Portico\portico-2.1.4\lib\portico.jar dosyasını kullanır.
RTI_HOME, Portico'nun lib klasörünün üst klasörü olmalıdır. Derlemede UTF-8
ve Java 11 hedefi seçilir. Eski kök .class dosyaları kullanılmaz; classpath build/.
Execution policy betiği engellerse tek çağrı için:

    powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1

Farklı bir koşuyu ayrı federasyon adıyla açabilirsin:

    .\run.ps1 -Federation TankSimulation2

Günlükler logs/<tarih>-<kimlik>/round-001/ altındadır; her yeniden başlatmada
round-002, round-003 gibi ayrı bir klasör açılır. Betik yalnız kendi başlattığı
süreçleri yönetir. build/integration/ altında test günlükleri tutulur.

KODU OKUMA SIRASI

1. SimulationConfig.java: sayılar, başlangıç konumları ve zaman aşımı ayarları.
2. TargetMotion.java / PlayerMotion.java / BulletMotion.java: yerel fizik.
3. SimulationMessages.java: süreçler arasında paylaşılan değişmez veriler.
4. TankFederate.java: asıl dünya, komutlar, saat ve nesne yaşam döngüsü.
5. TrajectoryMath.java: hedef sekmelerini parçalama ve sürekli temas hesabı.
6. PredictionFederate.java / CollisionDetectionFederate.java: hesap görevleri.
7. DisplayFederate.java / SimulationWindow.java: yerel görünüm ve Swing arayüzü.
8. FederateRuntime.java: HLA bağlantıları, callback, kuyruk, ilk değer isteği.
9. MessageCodec.java / SimulationStateCodec.java / SpatialEncoder.java /
   SpatialDecoder.java / EntityIdCodec.java: byte sözleşmeleri.
10. PhysicsTests.java / ProtocolTests.java / RuntimeDataTests.java /
    IntegrationTests.java / LateJoinTest.java / RestartTests.java: davranışın nasıl doğrulandığı.

Main ve RtiConnectionTest önceki küçük öğrenme denemeleridir; tam uygulamanın
giriş noktası run.ps1 ve dört *Federate sınıfıdır.

VERİ AKIŞI VE SAHİPLİK

Display --ControlCommand--> Tank --ShotSnapshot--> Prediction
Prediction --PredictedTrajectories--> Collision --CollisionResult--> Tank
CollisionResult ayrıca Display'e gider: tahmin sonucu olarak gösterilir.
Tank --GroundVehicle / Munition / SimulationState güncellemeleri--> Display
Tank --SimulationState--> Prediction ve Collision
Display / Prediction / Collision --FederateReady--> Tank

Yeniden başlatma bir HLA interaction'ı değildir. Display, RTI/worker kapanınca
son ekranda yerel RESTART/CLOSE komutlarını dinler. RESTART ile 10 çıkış kodunu
run.ps1'e verir; betik eski süreçleri kapatıp dört yeni JVM açar. Yeni Tank yeni
RunId üretir; önceki koşunun nesneleri ve callback kuyrukları yeni oyuna taşınmaz.

Tank, hedef ve oyuncu GroundVehicle instance'larının ve gerçek Munition
instance'larının tek sahibidir. Display yalnız görüntüler; Prediction hesaplar;
Collision yalnız karar verir. Nesne kaydı/silmesi ve gerçek hareket Tank'tadır.
Target/Player EntityIdentifier değerleri 1.1.1 ve 1.1.2; mermiler 1.1.3'ten
başlayarak ayrı kimlik alır. Rolleri object handle sayısından çıkarmıyoruz:
SimulationState içindeki TargetEntityId ve PlayerEntityId kullanılıyor.

FOM

fom/RPR2-D17_2010.xml: GroundVehicle, Munition, Spatial, EntityIdentifier.
fom/TankSim.xml: SimulationState ve beş HLAreliable/Receive interaction.
ATCSIM.xml bu uygulamada yüklenmez.

SimulationState: RunId, SimTime, Phase, MapWidth, MapHeight, StepSeconds,
TargetEntityId, PlayerEntityId. Tank yayımlar; diğer üç federe abonedir.
Display GroundVehicle ve Munition için Spatial/EntityIdentifier'a da abonedir.
Prediction yalnız ShotSnapshot'a, Collision yalnız PredictedTrajectories'e
abonedir; bu sürümde canlı hedef verisinden yeniden tahmin yapılmaz.

MessageCodec başındaki açıklama Payload'ın tam binary şemasını verir.
Interaction parametresi standart HLAopaqueData çerçevesindedir; içindeki
versiyonlu TankSim mesajları özel uygulama protokolüdür. RPR SpatialFPW ise
standart tipleriyle 60 byte olarak kodlanır. Başka bir uygulama TankSim
interaction'larını tüketmek isterse aynı Payload şemasını uygulamalıdır.

RTI bir geçmiş veri deposu değildir. Discovery sonrasında abone ilk değerleri
requestAttributeValueUpdate ile ister. Tank callback içinde işi kuyruğa alır;
RTI döngüsü son TAM değer önbelleğinden istenen attribute'ları yanıtlar. Normal
akışta yalnız değişen attribute'lar gönderilir. Alıcı partial patch'leri birleştirir.

THREAD DÜZENİ

Her federede:
    RTI thread: evokeMultipleCallbacks + callback + tüm RTI servis çağrıları
       callback -> byte dizilerini kopyala -> tek worker kuyruğu
    Worker: decode / saf hesap -> değişmez sonuç -> sonuç kuyruğu
    Main: sonuçları poll ile al -> kendi modelini değiştir
       main -> outbound kuyruğu -> RTI thread -> send/update/register/delete

Callback hesap bitmesini beklemez. Worker RTI çağırmaz ve dünyayı değiştirmez.
Tek worker callback sırasını korur. Worker kuyruğu dolarsa olay sessizce
kaybedilmez; hata bildirilir. Display'de ayrıca Swing EDT vardır; main ona
değişmez RenderSnapshot bırakır, EDT yalnız çizer ve kullanıcı komutlarını
kuyruğa ekler. Eski görüntü snapshot'ları yenisiyle değiştirilebilir; atış ve
çarpışma mesajları böyle düşürülmez.

SAAT, ATIŞ VE ÇARPIŞMA

Varsayılan harita 100x100; birim metre, hız birim/saniye. Hedef 10, hareketli
oyuncu 5, mermi dünya hızının büyüklüğü 50. Oyuncu başlangıçta sabittir.
Hedef merkezinin sınırdaki normal hız bileşeni ters döner; köşede ikisi de döner.
Oyuncu sınırda tamamen durur. Mermi merkezi ilk sınırına ulaştığında silinir.
Harita sınırları merkezler içindir; çarpışma yarıçapı duvarı içeri kaydırmaz.

Mermi oyuncunun merkezinde oluşturulur, dünya hızı oyuncunun hızına eklenmez.
Bu sürümde yalnız mermi-hedef teması aranır. Görsel tank ikonları temsilidir.
Temas modeli hedef için yarıçap 2, mermi için 0.2 olan dairelerdir.

SimTime uygulama saatidir; HLA logical time / Time Management kullanılmaz.
Adım varsayılan 0.05 s. Dört katılımcı katılıp uygulama hazır mesajlarını
gönderdikten sonra STARTING -> RUNNING geçilir; eski 10 saniyelik demo sınırı yok.

Ateşte Tank aynı t0'a ait hedef konumu/hızı, mermi konumu/hızı, harita boyutları,
yarıçaplar, RunId ve ShotId'yi tek ShotSnapshot'a koyar. Phase
WAITING_FOR_PREDICTION olur ve SimTime donar. RTI, main ve UI çalışmaya devam
eder; STOP her zaman işlenir. Beklemede yön değiştirilebilir ama dünya ilerlemez.
Bir hesap beklerken ikinci FIRE kabul edilmez. Önceki mermiler uçuşta kalabilir.

Prediction, hedef yolunu her duvar sekmesinde doğrusal parçalara böler; ufuk
merminin haritadan çıkışıdır. Collision aynı zaman aralıklarında iki dairenin
bağıl hareketini çözer ve ilk temas anını bulur. Yalnız yol çizgilerinin
kesişmesi yeterli değildir: cisimler aynı anda yakın olmalıdır.

HIT/NO_HIT gelecekteki olayın sonucudur. Tank sonucu alır almaz hedefi silmez;
simülasyon o zamana geldiğinde uygular. Olay tick'in ortasındaysa adım bölünür.
İlk isabet hedefi siler ve koşuyu bitirir. NO_HIT durumunda mermi çıkışında
silinir, simülasyon sürer. Yanlış RunId/ShotId, tekrar sonuç ve tekrar komutlar
uygulanmaz. Hesap hatası veya duvar-saati timeout ERROR üretir; ıskalama sayılmaz.
Bekleme bitince duvar saati deadline'ı yenilenir, duraklama süresi telafi edilmez.

PORTICO 2.1.4 UYUMLULUĞU

Federasyonu Tank oluşturur; diğerleri katılır. Başlangıçta dört sabit süreç
adı beklenir, sonra yayın/abonelik açılır. RTI_JOINED yalnız katılımı;
RTI_READY yayın/abonelik kurulumunu; uygulama RUNNING ise gerçek başlangıcı belirtir.

Bu kurulumda yeni katılım sırasında Portico FOM metadata nesnelerini yenileyip
eski pub/sub kayıtlarını etkileyebiliyor. PorticoCompatibility bunu küçük bir
uyumluluk sınıfında sınırlar: public Portico erişimcileriyle katılımcıları ve
FOM nesne referansını okur. Referans değişirse RTI thread'i yayın/abonelikleri
yeniden ilan eder. Bu dosya Portico'ya özgüdür; başka RTI'ye geçişte uyarlanmalıdır.
Sistem kurulumuna veya Portico jar dosyasına hiçbir yama uygulanmaz.

Kullanmadığımız MOM yönetim modeli, Portico'nun erken RoleCall sırasında null
hatası üretmesi nedeniyle bu JVM'de varsayılan disabled'dır. İstenirse
-Dportico.mom=enabled ile açılabilir. Tank/mermi HLA nesneleri bundan bağımsızdır.
Portico'nun ilk üye için "No co-ordinator found - appointing myself" uyarısı
tek başına başarısızlık değildir. Asıl hazır/phase satırları ve süreç çıkışı izlenir.

AYARLAR VE TESTLER

SimulationConfig.java -D property adlarını gösterir. Örnek:
    -Dtanksim.stepSeconds=0.05
    -Dtanksim.targetRadius=2
    -Dtanksim.bulletRadius=0.2
    -Dtanksim.target.x=40 -Dtanksim.target.y=20
    -Dtanksim.target.dirX=1 -Dtanksim.target.dirY=0

Display seçenekleri: --headless, --demo, -Dtanksim.autoFire=true,
-Dtanksim.autoStopSeconds=5. Bunlar kullanıcı komutu üretir; nişan alma veya
otoriter fizik Display'e taşınmaz. --demo tek atıştan sonra hit/çıkışla biter.

PhysicsTests: hızlar, dururken yön, sınır/köşe/çoklu sekme, tunneling, teğet,
göreli hızın sıfır olması, t0 teması, tahmin-gerçek hareket karşılaştırmaları.
ProtocolTests: binary çerçeve, beş mesaj tipi, sürüm, Unicode, bozuk paketler.
RuntimeDataTests: unsigned EntityId, partial State, Unicode ve FPW byte yerleşimi.
IntegrationTests: gerçek JVM/RTI ile hit, miss, hareket/durma/çoklu mermi,
eski ve gecikmiş sonuç, tekrar komut, hesap timeout ve beklemede STOP.
Test federe sınıfları IntegrationTests içinde kalır; normal run.ps1 kullanmaz.
LateJoinTest: dört federe çalışırken geç katılan ayrı test gözlemcisi tam
SimulationState ve sabit oyuncunun kimlik/Spatial değerlerini ister. Katılım
sonrasında yayın/abonelik yenilenmesi ve yeni atışın sonuçlanması da sınanır.
Bu beşinci gözlemci yalnız test aracıdır; normal uygulama dört federelidir.
