import hla.rti1516e.RTIambassador;
import java.util.*;
import org.portico.impl.hla1516e.Rti1516eAmbassador;
import org.portico.lrc.management.Federate;

/**
 * Kurulu Portico 2.1.4'e özgü iki okuma burada sınırlandırılmıştır.
 * Bu sürüm getFederateHandle(String) standart servisini uygulamıyor; ayrıca
 * üye katılımında FOM metadata nesneleri değişebiliyor. Sistem jar'ını yamamak
 * yerine mevcut public Portico erişimcilerini yalnız RTI thread'inde okuyoruz.
 * Başka bir RTI'ye geçerken uyarlanacak tek yer bu sınıftır.
 */
public final class PorticoCompatibility {
    private PorticoCompatibility() { }
    private static Rti1516eAmbassador portico(RTIambassador rti) {
        if (!(rti instanceof Rti1516eAmbassador))
            throw new IllegalStateException("Bu başlangıç uyarlaması Portico 2.1.4 içindir.");
        return (Rti1516eAmbassador) rti;
    }
    public static boolean allParticipantsJoined(RTIambassador rti) {
        Set<String> names = new HashSet<>();
        try {
            for (Federate federate : portico(rti).getHelper().getState().getFederation())
                names.add(federate.getFederateName());
        } catch (ConcurrentModificationException joinInProgress) {
            return false; // Portico'nun ağ thread'i listeyi güncelliyor; sonraki turda oku.
        }
        return names.containsAll(Arrays.asList("TankFederate", "DisplayFederate",
                "PredictionFederate", "CollisionFederate"));
    }
    public static Object modelToken(RTIambassador rti) {
        return portico(rti).getHelper().getFOM();
    }
}
