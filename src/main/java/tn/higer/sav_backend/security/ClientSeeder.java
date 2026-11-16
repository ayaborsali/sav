package tn.higer.sav_backend.security;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.ClientType;
import tn.higer.sav_backend.repository.ClientRepository;

import java.time.LocalDate;

/**
 * Insère les 6 clients de démonstration au premier démarrage.
 * Les identifiants d'origine (cli-1 … cli-6) sont conservés : les véhicules,
 * dossiers SAV et maintenances encore stockés dans le navigateur y font référence.
 */
@Component
public class ClientSeeder implements CommandLineRunner {

    private final ClientRepository clients;

    public ClientSeeder(ClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public void run(String... args) {
        if (clients.count() > 0) {
            System.out.println(">>> Table clients déjà remplie, on ne fait rien.");
            return;
        }

        System.out.println(">>> Création des clients de démonstration...");

        creer("cli-1", "CL-TN-TRST", "TRANSTU (Société des Transports de Tunis)",
                ClientType.PUBLIC_TRANSPORT,
                "Kamel Bouallegue (Dir. Technique & Maintenance)", "maintenance@transtu.tn",
                "+216 71 345 600", "Zone Industrielle Le Kram / Dépôt Central Tunis", "Le Kram, Tunis",
                "2024-01-15",
                "Exploitation intensive urbaine Grand Tunis (18h/jour). Contrat cadre pièces d’origine Higer.");
        creer("cli-2", "CL-TN-SNTRI", "SNTRI (Transport National Interurbain)",
                ClientType.PUBLIC_TRANSPORT,
                "Mongi Cherif (Chef Parc Autocars)", "m.cherif@sntri.tn",
                "+216 71 800 220", "Gare Routière Sud Bab Alioua", "Tunis",
                "2024-03-10",
                "Lignes grand confort Tunis - Sousse - Sfax - Gabès - Tozeur - Djerba.");
        creer("cli-3", "CL-TN-SRTS", "SRT Sousse (Société Régionale du Sahel)",
                ClientType.PUBLIC_TRANSPORT,
                "Nizar Belhadj", "technique@srt-sousse.com.tn",
                "+216 73 224 500", "Avenue Ibn Khaldoun, Sahloul", "Sousse",
                "2024-05-20",
                "Flotte urbaine et navettes inter-villes du Sahel (Monastir, Mahdia).");
        creer("cli-4", "CL-TN-AERO", "OACA & Tunisair Express (Navettes Tarmac)",
                ClientType.TOURISM,
                "Slim Trabelsi", "ops@oaca.nat.tn",
                "+216 71 754 000", "Aéroport International Tunis-Carthage", "Tunis",
                "2024-08-12",
                "Bus Higer Azure 12m 100% électriques pour transferts piste & parkings passagers.");
        creer("cli-5", "CL-TN-CART", "Carthage Tours & Navettes VIP",
                ClientType.TOURISM,
                "Amira Ben Mahmoud", "contact@carthagetours.tn",
                "+216 72 280 150", "Zone Touristique Yasmine Hammamet", "Nabeul",
                "2024-09-01",
                "Circuits touristiques grand tourisme et transferts VIP.");
        creer("cli-6", "CL-TN-SFAX", "SORETRAS Sfax (Régie Régionale de Transport)",
                ClientType.PUBLIC_TRANSPORT,
                "Hatem Ghorbel", "technique@soretras.com.tn",
                "+216 74 400 800", "Route de Gabès Km 1.5", "Sfax",
                "2024-09-10",
                "Dessertes urbaines et scolaires gouvernorat de Sfax.");

        System.out.println(">>> Terminé ! " + clients.count() + " clients créés.");
    }

    private void creer(String id, String code, String name, ClientType type,
                       String contactPerson, String email, String phone,
                       String address, String city, String createdAt, String notes) {
        Client c = new Client();
        c.setId(id);
        c.setCode(code);
        c.setName(name);
        c.setType(type);
        c.setContactPerson(contactPerson);
        c.setEmail(email);
        c.setPhone(phone);
        c.setAddress(address);
        c.setCity(city);
        c.setCreatedAt(LocalDate.parse(createdAt));
        c.setNotes(notes);
        clients.save(c);
        System.out.println(">>> Client créé : " + code);
    }
}
