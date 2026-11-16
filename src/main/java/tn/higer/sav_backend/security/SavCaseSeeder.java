package tn.higer.sav_backend.security;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SavPriority;
import tn.higer.sav_backend.repository.SavCaseRepository;

import java.time.LocalDate;

/**
 * Insère les 8 dossiers SAV de démonstration au premier démarrage.
 * Les identifiants d'origine (sav-1 … sav-8) sont conservés : les écrans du navigateur
 * (tableau de bord, rapports, détail véhicule) y font référence.
 */
@Component
public class SavCaseSeeder implements CommandLineRunner {

    private final SavCaseRepository savCases;

    public SavCaseSeeder(SavCaseRepository savCases) {
        this.savCases = savCases;
    }

    @Override
    public void run(String... args) {
        if (savCases.count() > 0) {
            System.out.println(">>> Table sav_cases déjà remplie, on ne fait rien.");
            return;
        }

        System.out.println(">>> Création des dossiers SAV de démonstration...");

        SavCase c1 = base("sav-1", "SAV-VIS-2026-0081", SavCaseType.VISITES,
                "Visite de contrôle des 80 000 km (Checklist Higer 45 points)",
                "veh-1", "cli-1", SavCaseStatus.IN_PROGRESS, SavPriority.MEDIUM,
                "2026-10-02", "2026-10-06", "Yassine Ben Salem",
                "Contrôle complet train avant, serrage pivots, vidange boîte Allison et remplacement cartouches dessiccateur Wabco à l’atelier Zone Industrielle Le Kram.");
        c1.setCostEstimate(1250.0);
        savCases.save(c1);

        SavCase c2 = base("sav-2", "SAV-VIS-2026-0079", SavCaseType.VISITES,
                "Visite semestrielle sécurité et système de freinage ABS/EBS",
                "veh-3", "cli-2", SavCaseStatus.CLOSED, SavPriority.LOW,
                "2026-09-20", "2026-09-22", "Mohamed Trabelsi",
                "Mesure de l’usure des plaquettes de freins (AV: 65%, AR: 75%). Purge des réservoirs d’air comprimé effectuée.");
        c2.setResolution("Véhicule certifié apte au service interurbain Tunis-Sfax.");
        savCases.save(c2);

        SavCase c3 = base("sav-3", "SAV-GAR-2026-0112", SavCaseType.GARANTIES,
                "Prise en charge échangeur thermique EGR fissuré sous garantie",
                "veh-10", "cli-6", SavCaseStatus.OPEN, SavPriority.URGENT,
                "2026-10-07", "2026-10-08", "Yassine Ben Salem",
                "Voyant moteur diagnostic code SPN 3058 FMI 10. Perte légère de liquide de refroidissement. Demande accord constructeur Higer.");
        c3.setWarrantyApprovalNumber("HGR-TN-WAR-26-8841");
        c3.setCostEstimate(4800.0);
        savCases.save(c3);

        SavCase c4 = base("sav-4", "SAV-GAR-2026-0098", SavCaseType.GARANTIES,
                "Remplacement compresseur climatisation Songz sous garantie",
                "veh-4", "cli-2", SavCaseStatus.APPROVED, SavPriority.HIGH,
                "2026-09-28", "2026-10-05", "Kamel Bouazizi",
                "Bruit anormal et embrayage électromagnétique bloqué. Pièce d’origine envoyée par le magasin central Higer Tunis.");
        c4.setWarrantyApprovalNumber("HGR-TN-WAR-26-8430");
        c4.setCostEstimate(5900.0);
        savCases.save(c4);

        SavCase c5 = base("sav-5", "SAV-QUA-2026-0044", SavCaseType.QUALITE,
                "Rapport non-conformité : serrure de porte médiane pneumatique",
                "veh-2", "cli-1", SavCaseStatus.IN_PROGRESS, SavPriority.MEDIUM,
                "2026-09-30", "2026-10-04", "Yassine Ben Salem",
                "Défaut de temporisation d’ouverture sur mécanisme de porte arrière lors des arrêts à chaud à Tunis. Bulletin technique appliqué.");
        savCases.save(c5);

        SavCase c6 = base("sav-6", "SAV-PIE-2026-0155", SavCaseType.PIECES,
                "Commande urgente : Filtres d’origine Higer & capteur ABS Knorr",
                "veh-3", "cli-2", SavCaseStatus.PENDING_PARTS, SavPriority.HIGH,
                "2026-10-05", "2026-10-08", "Magasin Central Tunis",
                "Expédition expresse vers l’atelier régional Sousse.");
        c6.setPartReference("HGR-3550-00892");
        c6.setQuantity(4);
        c6.setCostEstimate(1850.0);
        savCases.save(c6);

        SavCase c7 = base("sav-7", "SAV-REC-2026-0031", SavCaseType.RECLAMATIONS,
                "Réclamation client : vibration au freinage sur autoroute A1",
                "veh-4", "cli-2", SavCaseStatus.OPEN, SavPriority.URGENT,
                "2026-10-08", "2026-10-08", "Mohamed Trabelsi",
                "L’exploitant SNTRI signale un tremblement volant lors des décélérations autoroutières. Rectification ou remplacement des disques.");
        savCases.save(c7);

        SavCase c8 = base("sav-8", "SAV-REC-2026-0028", SavCaseType.RECLAMATIONS,
                "Réclamation sur délai de livraison connecteur charge rapide DC",
                "veh-7", "cli-4", SavCaseStatus.CLOSED, SavPriority.MEDIUM,
                "2026-09-12", "2026-09-18", "Kamel Bouazizi",
                "Livraison express effectuée par le distributeur Higer Tunisie et test haute tension certifié conforme à l’aéroport.");
        c8.setResolution("Connecteur installé avec succès et bus remis en circulation.");
        savCases.save(c8);

        System.out.println(">>> Terminé ! " + savCases.count() + " dossiers SAV créés.");
    }

    private SavCase base(String id, String caseNumber, SavCaseType type, String title,
                         String vehicleId, String clientId, SavCaseStatus status, SavPriority priority,
                         String createdAt, String updatedAt, String technician, String description) {
        SavCase c = new SavCase();
        c.setId(id);
        c.setCaseNumber(caseNumber);
        c.setType(type);
        c.setTitle(title);
        c.setVehicleId(vehicleId);
        c.setClientId(clientId);
        c.setStatus(status);
        c.setPriority(priority);
        c.setCreatedAt(LocalDate.parse(createdAt));
        c.setUpdatedAt(LocalDate.parse(updatedAt));
        c.setTechnician(technician);
        c.setDescription(description);
        System.out.println(">>> Dossier SAV créé : " + caseNumber);
        return c;
    }
}
