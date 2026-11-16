package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.InterventionPhoto;

import java.time.LocalDateTime;

/**
 * Preuves photo d'une sortie de dépannage.
 *
 * Ce qu'un écran ne peut pas écrire : l'empreinte du fichier, l'heure du versement
 * et l'identité de celui qui a versé. Le serveur les calcule tous les trois, sinon
 * la « preuve » serait une déclaration.
 *
 * L'image n'est pas dans la réponse : elle se récupère par l'adresse {@code url}.
 * Cette adresse est relative à celle de l'API, sans le préfixe {@code /api} : c'est
 * l'écran qui le connaît (configuration d'environnement), pas le serveur. Le
 * navigateur charge l'image avec le jeton (le rôle CONSULTATION n'y a pas droit).
 */
public final class PhotoDtos {

    private PhotoDtos() {}

    /** Angle du cliché, en minuscules côté écran (« before », « after », « mileage », « other »). */
    public record PhotoAngleRequest(@NotBlank @Size(max = 20) String angle,
                                    @Size(max = 500) String note) {}

    public record PhotoResponse(String id, String interventionId, String angle, String originalName,
                                String contentType, Long sizeBytes, String sha256,
                                LocalDateTime takenAt, String takenAtSource, LocalDateTime uploadedAt,
                                String actorEmail, String note, String url) {

        public static PhotoResponse from(InterventionPhoto p) {
            return new PhotoResponse(p.getId(), p.getInterventionId(),
                    p.getAngle() != null ? p.getAngle().name().toLowerCase() : null,
                    p.getOriginalName(), p.getContentType(), p.getSizeBytes(), p.getSha256(),
                    p.getTakenAt(),
                    p.getTakenAtSource() != null ? p.getTakenAtSource().name().toLowerCase() : null,
                    p.getUploadedAt(), p.getActorEmail(), p.getNote(),
                    "/intervention-photos/" + p.getId() + "/content");
        }
    }

    /**
     * Ce qui manque comme preuve avant de clôturer. {@code ok} sert à l'affichage :
     * l'application signale, un humain décide — il peut cloturer, mais il doit alors
     * écrire pourquoi les photos ne sont pas là.
     */
    public record ProofStatusDto(boolean ok, java.util.List<String> missing, String message) {}
}
