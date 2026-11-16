package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tn.higer.sav_backend.model.Document;
import tn.higer.sav_backend.model.DocumentCategory;

import java.time.LocalDate;

public final class DocumentDtos {

    private DocumentDtos() {}

    /**
     * Création / modification d'un document (admin et gestionnaire_sav).
     * La catégorie arrive en minuscules depuis Angular (« carte_grise ») —
     * accept-case-insensitive-enums est activé dans application.properties.
     */
    public record DocumentRequest(@NotBlank String title,
                                  @NotNull DocumentCategory category,
                                  String fileType,
                                  String fileSize,
                                  String vehicleId,
                                  String clientId,
                                  LocalDate uploadedAt,
                                  String uploadedBy,
                                  String fileData) {}

    public record DocumentResponse(String id, String title, String category, String fileType,
                                   String fileSize, String vehicleId, String clientId,
                                   LocalDate uploadedAt, String uploadedBy, String fileData) {

        public static DocumentResponse from(Document d) {
            return new DocumentResponse(
                    d.getId(), d.getTitle(),
                    d.getCategory() != null ? d.getCategory().name().toLowerCase() : null,
                    d.getFileType(), d.getFileSize(), d.getVehicleId(), d.getClientId(),
                    d.getUploadedAt(), d.getUploadedBy(), d.getFileData());
        }
    }

    /**
     * Projection légère renvoyée par la liste paginée GET /api/documents.
     * Elle omet fileData (le fichier en base64) : le contenu binaire n'est plus
     * expédié dans chaque ligne de liste. Il reste accessible via
     * GET /api/documents/{id} (DTO complet) et GET /api/documents/{id}/content (octets bruts).
     */
    public record DocumentSummary(String id, String title, String category, String fileType,
                                  String fileSize, String vehicleId, String clientId,
                                  LocalDate uploadedAt, String uploadedBy) {

        public static DocumentSummary from(Document d) {
            return new DocumentSummary(
                    d.getId(), d.getTitle(),
                    d.getCategory() != null ? d.getCategory().name().toLowerCase() : null,
                    d.getFileType(), d.getFileSize(), d.getVehicleId(), d.getClientId(),
                    d.getUploadedAt(), d.getUploadedBy());
        }
    }
}
