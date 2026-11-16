package tn.higer.sav_backend.security;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.repository.MaintenanceRepository;

import java.time.LocalDate;

/**
 * Insère les 3 documents de maintenance de démonstration au premier démarrage
 * (un devis, un bon de livraison, un ordre de travail).
 * Les identifiants d'origine (maint-1 … maint-3) sont conservés.
 */
@Component
public class MaintenanceSeeder implements CommandLineRunner {

    private final MaintenanceRepository maintenance;

    public MaintenanceSeeder(MaintenanceRepository maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    public void run(String... args) {
        if (maintenance.count() > 0) {
            System.out.println(">>> Table maintenance_items déjà remplie, on ne fait rien.");
            return;
        }

        System.out.println(">>> Création des documents de maintenance de démonstration...");

        MaintenanceItem m1 = base("maint-1", MaintenanceType.DEVIS, "DEV-2026-0104",
                "veh-5", "cli-3", "2026-10-04", MaintenanceStatus.VALIDATED,
                4800.0, 5712.0, "Yassine Ben Salem",
                "Remplacement kit embrayage complet Sachs et roulement pilote volant moteur.");
        ligne(m1, "HGR-CLU-3850", "Kit mécanisme et disque embrayage 430mm Higer", 1.0, 3600.0);
        ligne(m1, "HGR-BRG-1209", "Butée d’embrayage renforcée", 1.0, 600.0);
        ligne(m1, "MO-MEC-01", "Main d’œuvre qualification constructeur (6h)", 1.0, 600.0);
        maintenance.save(m1);

        MaintenanceItem m2 = base("maint-2", MaintenanceType.BL, "BL-2026-0087",
                "veh-1", "cli-1", "2026-10-02", MaintenanceStatus.DELIVERED,
                1850.0, 2201.0, "Kamel Bouallegue",
                "Dotation filtres et pièces d’usure révision intermédiaire TRANSTU.");
        ligne(m2, "HGR-FLT-OIL-02", "Filtre à huile moteur Cummins ISL", 6.0, 110.0);
        ligne(m2, "HGR-FLT-FUEL-01", "Préfiltre séparateur d’eau Fleetguard", 6.0, 140.0);
        ligne(m2, "HGR-BELT-V08", "Courroie d’accessoire trapézoïdale poly-V", 2.0, 175.0);
        maintenance.save(m2);

        MaintenanceItem m3 = base("maint-3", MaintenanceType.OPERATION, "OR-2026-0215",
                "veh-4", "cli-2", "2026-10-06", MaintenanceStatus.IN_PROGRESS,
                7200.0, 8568.0, "Mohamed Trabelsi",
                "Remplacement compresseur climatisation, tirage au vide circuit frigo et recharge gaz R134a (6.5 kg).");
        ligne(m3, "HGR-AC-CMP-04", "Compresseur Songz SP-1700 d’origine", 1.0, 5800.0);
        ligne(m3, "FLUID-R134A", "Fluide frigorigène écologique R134a (kg)", 6.5, 85.0);
        ligne(m3, "MO-CLIM-01", "Main d’œuvre tirage au vide et test étanchéité", 1.0, 847.5);
        maintenance.save(m3);

        System.out.println(">>> Terminé ! " + maintenance.count() + " documents de maintenance créés.");
    }

    private MaintenanceItem base(String id, MaintenanceType type, String number,
                                 String vehicleId, String clientId, String date, MaintenanceStatus status,
                                 double totalHT, double totalTTC, String technician, String description) {
        MaintenanceItem m = new MaintenanceItem();
        m.setId(id);
        m.setType(type);
        m.setNumber(number);
        m.setVehicleId(vehicleId);
        m.setClientId(clientId);
        m.setDate(LocalDate.parse(date));
        m.setStatus(status);
        m.setTotalHT(totalHT);
        m.setTotalTTC(totalTTC);
        m.setTechnician(technician);
        m.setDescription(description);
        System.out.println(">>> Document maintenance créé : " + number);
        return m;
    }

    private void ligne(MaintenanceItem m, String reference, String label, double quantity, double unitPrice) {
        MaintenanceDetail d = new MaintenanceDetail();
        d.setReference(reference);
        d.setLabel(label);
        d.setQuantity(quantity);
        d.setUnitPrice(unitPrice);
        m.getDetails().add(d);
    }
}
