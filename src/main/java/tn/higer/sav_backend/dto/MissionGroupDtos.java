package tn.higer.sav_backend.dto;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Les aller-retours de l'écran Mission groupe.
 *
 * Le dossier porte le déplacement : le client du portefeuille, l'homme et la
 * voiture affectés, le départ prévu par l'atelier, puis le départ réel, le
 * retour et les photos. Chaque bus du dossier porte sa propre mission :
 * le problème et le diagnostic.
 *
 * Ce qui ne se saisit pas : les kilomètres parcourus et les états « en
 * déplacement » / « rentré ». Les premiers se soustraient, les seconds se lisent
 * sur les dates. Une valeur tapée à la main ne prouverait rien de plus que le
 * papier qu'elle recopie.
 */
public final class MissionGroupDtos {

    private MissionGroupDtos() {}

    /**
     * Une ligne de bus à la création : le bus choisi dans ceux du client, et le
     * problème constaté. L'immatriculation n'est pas recopiée — le serveur la
     * relit sur la fiche du bus.
     */
    public record BusLineRequest(@Size(max = 64) String vehicleId,
                             @NotBlank @Size(max = 4000) String description,
                             @Size(max = 4000) String diagnosis,
                             @Size(max = 120) String registration) {

    /** Constructeur historique : un bus choisi dans le portefeuille, sans plaque libre. */
    public BusLineRequest(String vehicleId, String description, String diagnosis) {
        this(vehicleId, description, diagnosis, null);
    }

    @AssertTrue(message = "Choisissez un bus du client ou saisissez son immatriculation")
    public boolean isVehicleOrRegistrationPresent() {
        return (vehicleId != null && !vehicleId.isBlank())
            || (registration != null && !registration.isBlank());
    }

}

    /**
     * Préparer le dossier. Le numéro peut rester vide : le serveur propose alors
     * le suivant. S'il est fourni, il est gardé tel quel — c'est le numéro du
     * papier — et refusé s'il est déjà pris.
     *
     * Le départ prévu est fixé ici ; le départ réel, le retour et les photos
     * sont pointés plus tard par le technicien affecté.
     *
     * La fiche atelier s'écrit ici, une ligne par chose à faire : c'est l'atelier
     * qui sait ce qu'il y a à vérifier sur place, et l'homme coche.
     *
     * Le client est soit choisi au portefeuille ({@code clientId}), soit saisi à la
     * main quand il n'y est pas : {@code clientName} est alors obligatoire, avec son
     * adresse, sa ville et son téléphone — c'est avec eux que l'homme appelle et
     * trace son itinéraire. Un client du portefeuille garde, lui, sa fiche.
     */
    public record DossierRequest(@Size(max = 40) String number,
                                 @Size(max = 64) String clientId,
                                 @NotNull LocalDateTime plannedDepartureAt,
                                 @NotBlank @Size(max = 64) String technicianUserId,
                                 @NotBlank @Size(max = 64) String serviceVehicleId,
                                 @NotEmpty List<@Valid BusLineRequest> buses,
                                 List<@NotBlank @Size(max = 300) String> checklist,
                                 @Size(max = 40) String callChannel,
                                 LocalDateTime plannedReturnAt,
                                 @PositiveOrZero Long plannedDepartureMileage,
                                 @PositiveOrZero Long plannedReturnMileage,
                                 @Size(max = 255) String clientName,
                                 @Size(max = 255) String clientAddress,
                                 @Size(max = 255) String clientCity,
                                 @Size(max = 255) String clientPhone) {

        /** Constructeur historique : un client du portefeuille, sans coordonnées saisies. */
        public DossierRequest(String number, String clientId, LocalDateTime plannedDepartureAt,
                              String technicianUserId, String serviceVehicleId,
                              List<BusLineRequest> buses, List<String> checklist,
                              String callChannel, LocalDateTime plannedReturnAt,
                              Long plannedDepartureMileage, Long plannedReturnMileage) {
            this(number, clientId, plannedDepartureAt, technicianUserId, serviceVehicleId,
                    buses, checklist, callChannel, plannedReturnAt,
                    plannedDepartureMileage, plannedReturnMileage, null, null, null, null);
        }
    }

    /**
     * Le départ réel, pointé devant la voiture : l'heure, puis deux photos
     * versées avant l'enregistrement — la voiture et son compteur. Le chiffre
     * reste facultatif : la photo prouve le départ, et le kilométrage ne sert
     * qu'à calculer les kilomètres parcourus si les deux chiffres existent.
     */
    public record DepartRequest(@NotNull LocalDateTime departedAt,
                                @PositiveOrZero Long departureMileage) {}

    /** Le diagnostic se complète au retour, bus par bus ; le problème peut être précisé. */
    public record BusDiagnosisRequest(@NotBlank String id,
                                      @Size(max = 4000) String description,
                                      @Size(max = 8000) String diagnosis) {}

    /**
     * Enregistrer le retour. Même règle qu'au départ : deux photos — la voiture
     * et son compteur — puis l'enregistrement. La date reste obligatoire, le
     * chiffre facultatif ; s'il est donné des deux côtés, le serveur contrôle
     * que le compteur n'a pas reculé et calcule les kilomètres parcourus.
     */
    public record RetourRequest(@NotNull LocalDateTime returnedAt,
                                @PositiveOrZero Long returnMileage,
                                List<@Valid BusDiagnosisRequest> buses) {}

    /**
     * Le constat d'un bus, écrit sur place : le code d'erreur lu, la pièce en
     * cause, et ce que l'homme a vu et fait. Les deux photos du bus — avant et
     * après le travail — sont versées avant l'enregistrement.
     */
    public record ConstatRequest(@Size(max = 80) String defectCode,
                                 @Size(max = 255) String causingPart,
                                 @Size(max = 8000) String observations) {}

    /** Cocher ou décocher une ligne de la fiche atelier. */
    public record CocherRequest(@NotNull Boolean done) {}

    /**
     * Annuler un dossier encore à l'atelier. Le motif est exigé : c'est lui qui
     * explique, des semaines plus tard, pourquoi une visite préparée n'a pas eu
     * lieu. Le dossier reste, avec son numéro et sa ligne.
     */
    public record AnnulationRequest(@NotBlank @Size(max = 500) String reason) {}

    public record BusResponse(String id, String vehicleId, String registration,
                              String description, String diagnosis,
                              String defectCode, String causingPart, String observations) {}

    /** Une ligne de la fiche atelier vue par l'écran. */
    public record ChecklistItemResponse(String id, Integer position, String label,
                                        boolean done, String doneAt, String doneBy) {}

    /**
     * Une preuve photo. {@code url} se charge avec le jeton : l'adresse seule ne
     * suffit pas à ouvrir l'image, et c'est voulu.
     */
    public record PhotoResponse(String id, String dossierId, String busId,
                                String angle, String angleLabel,
                                String originalName, String contentType, Long sizeBytes, String sha256,
                                String takenAt, String takenAtSource, String uploadedAt,
                                String actorEmail, String note, String url) {

        public static PhotoResponse from(tn.higer.sav_backend.model.MissionGroupPhoto photo) {
            return new PhotoResponse(photo.getId(), photo.getDossierId(), photo.getBusId(),
                    photo.getAngle() == null ? null : photo.getAngle().name().toLowerCase(),
                    photo.getAngle() == null ? null : photo.getAngle().label(),
                    photo.getOriginalName(), photo.getContentType(), photo.getSizeBytes(),
                    photo.getSha256(),
                    photo.getTakenAt() == null ? null : photo.getTakenAt().withNano(0).toString(),
                    photo.getTakenAtSource() == null ? null
                            : photo.getTakenAtSource().name().toLowerCase(),
                    photo.getUploadedAt() == null ? null : photo.getUploadedAt().withNano(0).toString(),
                    photo.getActorEmail(), photo.getNote(),
                    "/mission-group-photos/" + photo.getId() + "/content");
        }
    }

    /**
     * La fiche atelier jointe au dossier — le papier ou le PDF d'origine des
     * tâches. {@code url} se charge avec le jeton : l'adresse seule ne suffit
     * pas à ouvrir la fiche, et c'est voulu.
     */
    public record AttachmentResponse(String id, String dossierId,
                                     String originalName, String contentType, Long sizeBytes,
                                     String sha256, String uploadedAt, String actorEmail, String url) {

        public static AttachmentResponse from(tn.higer.sav_backend.model.MissionGroupAttachment piece) {
            return new AttachmentResponse(piece.getId(), piece.getDossierId(),
                    piece.getOriginalName(), piece.getContentType(), piece.getSizeBytes(),
                    piece.getSha256(),
                    piece.getUploadedAt() == null ? null : piece.getUploadedAt().withNano(0).toString(),
                    piece.getActorEmail(),
                    "/mission-group-attachments/" + piece.getId() + "/content");
        }
    }

    /**
     * Une ligne de l'écran. {@code departedAt} reste vide tant que le technicien
     * n'a pas pointé le départ réel ; {@code traveledKm} tant que le dossier
     * n'est pas rentré. {@code stateLabel} dit où en est le pointage ;
     * {@code status} et {@code statusLabel} portent les étapes du papier —
     * planifiée, en cours, constat renseigné, clôturée, annulée — déduites elles
     * aussi des dates et du motif, jamais écrites en double.
     *
     * L'adresse, la ville et le téléphone sont ceux recopiés le jour du papier :
     * l'écran les affiche tels quels, il ne relit pas la fiche du client.
     */
    public record DossierResponse(String id, String number, String clientId, String clientName,
                                  String clientAddress, String clientCity, String clientPhone,
                                  String callChannel, String callChannelLabel,
                                  String plannedDepartureAt, String plannedReturnAt,
                                  Long plannedDepartureMileage, Long plannedReturnMileage,
                                  String technicianUserId, String technicianName,
                                  String serviceVehicleId, String serviceVehicleRegistration,
                                  String departedAt, Long departureMileage,
                                  String returnedAt, Long returnMileage, Long traveledKm,
                                  String state, String stateLabel,
                                  String status, String statusLabel,
                                  String cancelledAt, String cancelReason, String cancelledBy,
                                  List<BusResponse> buses, List<PhotoResponse> photos,
                                  List<ChecklistItemResponse> checklist, boolean checklistIncomplete,
                                  List<AttachmentResponse> attachments,
                                  String createdBy, String createdAt) {}

    /**
     * Ce qui est déjà pris sur une période, avec le motif à afficher. Ces choix
     * restent visibles et sélectionnables : l'atelier peut enregistrer malgré le
     * chevauchement, qui sera conservé au journal de contrôle.
     */
    public record AvailabilityResponse(List<String> busyTechnicianUserIds,
                                       List<String> busyServiceVehicleIds,
                                       Map<String, String> busyTechnicianReasons,
                                       Map<String, String> busyServiceVehicleReasons) {}

    /** Une ligne de l'historique d'un bus : ce qui a été fait, quand, et sur quel dossier. */
    public record HistoryRow(String dossierId, String number, String departedAt,
                             String registration, String description, String diagnosis,
                             String returnedAt, Long traveledKm) {}

    /** Le numéro proposé par le serveur ; l'écran peut le remplacer par celui du papier. */
    public record NextNumberResponse(String number) {}

    /**
     * Une ligne du suivi du téléphone : de quoi dire « un dossier vous est
     * affecté » et « votre départ prévu est passé », sans tirer à chaque
     * relecture les bus, photos et pièces jointes de chaque dossier.
     */
    public record MyDossierRow(String id, String number, String clientName,
                               String createdAt, String plannedDepartureAt,
                               String departedAt, String returnedAt) {}
}
