package tn.higer.sav_backend.security;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.model.VehicleStatus;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.VehicleMileageService;

import java.time.LocalDate;

/**
 * Insère les 10 véhicules de démonstration au premier démarrage.
 * Les identifiants d'origine (veh-1 … veh-10) sont conservés : les dossiers SAV,
 * documents de maintenance et documents encore stockés dans le navigateur y font référence.
 * Les garanties de veh-3, veh-4 et veh-9 expirent bientôt (dates calculées) pour la démo.
 */
@Component
public class VehicleSeeder implements CommandLineRunner {

    private final VehicleRepository vehicles;
    private final VehicleMileageService compteur;

    public VehicleSeeder(VehicleRepository vehicles, VehicleMileageService compteur) {
        this.vehicles = vehicles;
        this.compteur = compteur;
    }

    @Override
    public void run(String... args) {
        if (vehicles.count() > 0) {
            System.out.println(">>> Table vehicles déjà remplie, on ne fait rien.");
            return;
        }

        System.out.println(">>> Création des véhicules de démonstration...");

        creer("veh-1", "LJDF2E197RA103942", "215 TU 8920",
                "Standard", 2024, "cli-1", 74200L,
                "2024-02-10", "2027-02-10", "Diesel Cummins", VehicleStatus.ACTIVE,
                "Boîte Allison automatique, climatisation Bitzer tropicalisée Tunisie.");
        creer("veh-2", "LJDF2E198RA104812", "215 TU 8921",
                "Standard", 2024, "cli-1", 81500L,
                "2024-02-10", "2027-02-10", "Diesel Cummins", VehicleStatus.ACTIVE,
                null);
        creer("veh-3", "LJDF3B205SB201104", "222 TU 4105",
                "Confort", 2024, "cli-2", 112000L,
                "2024-04-01", LocalDate.now().plusDays(25).toString(), "Diesel Scania", VehicleStatus.ACTIVE,
                "Autocar grand tourisme 51 places climatisé avec toilettes.");
        creer("veh-4", "LJDF3B206SB201115", "222 TU 4106",
                "Confort", 2024, "cli-2", 108400L,
                "2024-04-01", LocalDate.now().plusDays(38).toString(), "Diesel Scania", VehicleStatus.IN_WORKSHOP,
                "En atelier central Tunis pour révision compresseur Songz.");
        creer("veh-5", "LJDE1A102QA982011", "198 TU 3201",
                "Standard", 2023, "cli-3", 145000L,
                "2023-06-15", "2025-06-15", "Diesel Cummins", VehicleStatus.ACTIVE,
                "Garantie terminée, sous contrat de maintenance préventive SRT Sousse.");
        creer("veh-6", "LJDE1A103QA982025", "198 TU 3202",
                "Standard", 2023, "cli-3", 152300L,
                "2023-06-15", "2025-06-15", "Diesel Cummins", VehicleStatus.ACTIVE,
                null);
        creer("veh-7", "LJDE9E300SA330101", "228 TU 7500",
                "Confort", 2024, "cli-4", 43000L,
                "2024-09-01", "2029-09-01", "Électrique CATL", VehicleStatus.ACTIVE,
                "Pack batteries CATL 350kWh, borne de recharge rapide Tunis-Carthage.");
        creer("veh-8", "LJDE9E301SA330102", "228 TU 7501",
                "Confort", 2024, "cli-4", 41200L,
                "2024-09-01", "2029-09-01", "Électrique CATL", VehicleStatus.ACTIVE,
                null);
        creer("veh-9", "LJDF1C180RA200450", "219 TU 6100",
                "Confort", 2024, "cli-5", 62000L,
                "2024-09-15", LocalDate.now().plusDays(48).toString(), "Diesel Cummins", VehicleStatus.ACTIVE,
                null);
        creer("veh-10", "LJDF1C181RA200451", "219 TU 6101",
                "Standard", 2024, "cli-6", 59800L,
                "2024-09-15", "2027-09-15", "Diesel Cummins", VehicleStatus.IMMOBILIZED,
                "Attente injecteur rampe commune sous accord de garantie constructeur.");

        System.out.println(">>> Terminé ! " + vehicles.count() + " véhicules créés.");
    }

    private void creer(String id, String vin, String registration, String model, int year,
                       String clientId, Long mileage, String commissioningDate, String warrantyEndDate,
                       String motorization, VehicleStatus status, String notes) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setVin(vin);
        v.setRegistration(registration);
        v.setModel(model);
        v.setYear(year);
        v.setClientId(clientId);
        // Valeur de départ écrite par la porte du compteur, comme toute autre : un
        // écriveur direct de mileage ferait renaître le deux-chiffres-comme-avant.
        compteur.saisieAtelier(v, mileage, null, null);
        v.setCommissioningDate(LocalDate.parse(commissioningDate));
        v.setWarrantyEndDate(LocalDate.parse(warrantyEndDate));
        v.setMotorization(motorization);
        v.setStatus(status);
        v.setNotes(notes);
        vehicles.save(v);
        System.out.println(">>> Véhicule créé : " + registration);
    }
}
