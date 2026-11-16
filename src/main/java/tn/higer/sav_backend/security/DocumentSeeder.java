package tn.higer.sav_backend.security;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tn.higer.sav_backend.model.Document;
import tn.higer.sav_backend.model.DocumentCategory;
import tn.higer.sav_backend.repository.DocumentRepository;

import java.time.LocalDate;

/**
 * Insère les 5 documents de démonstration au premier démarrage.
 * Les identifiants d'origine (doc-1 … doc-5) sont conservés.
 */
@Component
public class DocumentSeeder implements CommandLineRunner {

    private final DocumentRepository documents;

    public DocumentSeeder(DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public void run(String... args) {
        if (documents.count() > 0) {
            System.out.println(">>> Table documents déjà remplie, on ne fait rien.");
            return;
        }

        System.out.println(">>> Création des documents de démonstration...");

        creer("doc-1", "Certificat de Garantie Constructeur Higer Tunisie - Châssis LJDF2E197RA103942",
                DocumentCategory.GARANTIE, "PDF", "1.4 Mo", "veh-1", "cli-1",
                "2024-02-12", "Responsable Homologation Tunis");
        creer("doc-2", "Manuel d’Atelier & Schémas Électriques Multiplexage Higer KLQ6129G",
                DocumentCategory.SCHEMA_ELECTRIQUE, "PDF", "8.7 Mo", "veh-1", "cli-1",
                "2024-03-01", "Support Technique Higer Tunisie");
        creer("doc-3", "Procédure Sécurité Haute Tension & Pack Batterie CATL Azure 12m",
                DocumentCategory.MANUEL, "PDF", "4.2 Mo", "veh-7", "cli-4",
                "2024-09-05", "Expert Véhicules Électriques OACA");
        creer("doc-4", "Rapport Contrôle Technique & Épreuve Freinage N°26-08 (Agence Tunis)",
                DocumentCategory.RAPPORT_EXPERTISE, "PDF", "950 Ko", "veh-3", "cli-2",
                "2026-09-22", "Bureau Contrôle Technique Agréé");
        creer("doc-5", "Carte Grise & Certificat de Conformité Ministère du Transport",
                DocumentCategory.CARTE_GRISE, "PDF", "720 Ko", "veh-4", "cli-2",
                "2024-04-05", "Service Immatriculation Tunis");

        System.out.println(">>> Terminé ! " + documents.count() + " documents créés.");
    }

    private void creer(String id, String title, DocumentCategory category, String fileType,
                       String fileSize, String vehicleId, String clientId,
                       String uploadedAt, String uploadedBy) {
        Document d = new Document();
        d.setId(id);
        d.setTitle(title);
        d.setCategory(category);
        d.setFileType(fileType);
        d.setFileSize(fileSize);
        d.setVehicleId(vehicleId);
        d.setClientId(clientId);
        d.setUploadedAt(LocalDate.parse(uploadedAt));
        d.setUploadedBy(uploadedBy);
        documents.save(d);
        System.out.println(">>> Document créé : " + title);
    }
}
