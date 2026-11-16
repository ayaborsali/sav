package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SavPriority;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Allers-retours du module Dépannage.
 *
 * Deux règles structurent ces DTOs :
 * 1. aucune heure d'étape n'est une champ de saisie : les *At sortent en
 *    réponse, ils n'entrent que par les boutons d'étape (DispatchRequest,
 *    DepartRequest, …). Un humain ne peut pas écrire « je suis parti à 9 h » ;
 * 2. les durées sont calculées serveur à partir de ces horodatages. C'est ce
 *    qui permet de comparer un temps réellement passé et une prime.
 *
 * Les libellés (bus, client, technicien, voiture de déplacement) sont joints par
 * le serveur : l'écran n'a pas à faire cinq requêtes pour afficher une ligne,
 * et un identifiant seul ne se contrôle pas à l'œil.
 */
public final class InterventionDtos {

    private InterventionDtos() {}

    /**
     * Ouverture d'une sortie. Le nom de l'homme n'est plus exigé ici : une sortie
     * naît le plus souvent d'un dossier SAV ouvert au guichet, au moment où
     * l'atelier ne sait pas encore qui sera libre. L'affectation — la voiture et
     * l'homme, ensemble — se fait à l'étape suivante, et c'est là que le nom doit
     * apparaître. Une sortie qui resterait sans nom ne se contrôle pas et ne se
     * paie pas : le serveur le refuse au départ.
     *
     * {@code technicianUserId} est le COMPTE de la personne (table `users`), pas
     * l'identifiant de sa fiche de paie : le chef d'atelier choisit quelqu'un qui
     * existe et qui se connecte. La fiche est reliée ou créée par le serveur au
     * moment de l'affectation.
     */
    public record InterventionRequest(
            @NotBlank String vehicleId,
            UUID technicianUserId,
            String serviceVehicleId,
            LocalDateTime reportedAt,
            @Size(max = 120) String requestedBy,
            @Size(max = 500) String locationAddress,
            @Size(max = 120) String locationCity,
            @Size(max = 2000) String description,
            SavPriority priority,
            Long busMileage,
            String clientId) {}

    /**
     * L'affectation en un geste : la voiture engagée et l'homme qui part. Le
     * technicien peut rester vide quand la sortie porte déjà un nom repris du
     * dossier SAV ; le serveur refuse alors une voiture sans personne.
     *
     * La fiche atelier s'écrit ici, une ligne par chose à faire sur place ;
     * l'homme la coche, et une ligne oubliée reste rouge sans bloquer le retour.
     */
    public record DispatchRequest(@NotBlank String serviceVehicleId, Long startMileage,
                                  UUID technicianUserId,
                                  List<@NotBlank @Size(max = 300) String> checklist) {}

    /**
     * Envoyer une équipe sur un rappel de palier. Le nom est exigé ici : une sortie
     * d'entretien sans homme ne se contrôle pas et ne se paie pas, et personne n'a
     * intérêt à laisser traîner une sortie « en attente » qui ne partira jamais.
     *
     * Rien d'autre ne se saisit : le bus et le palier viennent du rappel, l'heure de
     * départ se posera par le bouton du technicien.
     */
    public record EnvoyerRequest(@NotBlank String serviceVehicleId, @NotNull UUID technicianUserId) {}

    /**
     * « Je pars » : la voiture et son compteur sont photographiés avant
     * l'enregistrement. Le chiffre reste facultatif — la photo prouve le départ,
     * et le chiffre ne sert qu'à calculer la distance s'il est relevé.
     */
    public record DepartRequest(Long startMileage) {}

    /** Arrivée sur place. */
    public record ArrivalRequest(Long busMileage, @Size(max = 500) String note) {}

    /** Diagnostic : ce qui est constaté, le code défaut et la pièce en cause. */
    public record ProgressRequest(@Size(max = 40) String defectCode,
                                  @Size(max = 2000) String diagnosis,
                                  @Size(max = 120) String causingPart) {}

    /** Issue de l'intervention : « repaired » ou « towed » — le serveur refuse les deux à la fois. */
    public record FinishRequest(@NotBlank @Size(max = 20) String result,
                                @Size(max = 1000) String resultNote,
                                Long busMileageEnd) {}

    /**
     * Retour à l'atelier : la voiture et son compteur sont photographiés avant
     * l'enregistrement. Le chiffre reste facultatif, et qui reprend le bus
     * s'écrit ici.
     */
    public record ReturnRequest(Long endMileage,
                                @Size(max = 120) String receivedBy,
                                @Size(max = 500) String note) {}

    /**
     * La rentrée constatée : la voiture est revenue mais l'homme n'a pointé aucune
     * heure (téléphone perdu, application jamais ouverte, sortie d'essai). La raison
     * est obligatoire : les heures qui manquent manqueront pour toujours, et cette
     * phrase est la seule chose qui expliquera la sortie au registre.
     */
    public record DeclareReturnRequest(@NotBlank @Size(max = 500) String reason) {}

    /** Cocher ou décocher une ligne de la fiche atelier d'une sortie. */
    public record CocherRequest(@NotNull Boolean done) {}

    /** Une ligne de la fiche atelier, telle que l'écran la lit. */
    public record ChecklistItemResponse(String id, Integer position, String label,
                                        boolean done, String doneAt, String doneBy) {}

    /**
     * Clôture, réservée au chef d'atelier et à l'ADMIN.
     *
     * Le rattachement au dossier SAV se fait ici, de deux manières exclusives :
     * soit {@code savCaseId} (un dossier qui existe déjà), soit {@code openSavCase}
     * (le serveur crée le dossier avec ce que la sortie sait déjà). Les deux à la
     * fois serait un choix non décidé, et une sortie clôturée sans rien d'écrit
     * reste licite : toutes les sorties ne finissent pas en dossier.
     */
    public record ClosureRequest(@Size(max = 1000) String closureNote,
                                 String savCaseId,
                                 Boolean openSavCase,
                                 SavCaseType savCaseType,
                                 @Size(max = 500) String missingProofReason) {}

    /** Note interne du chef d'atelier (le journal garde l'auteur et l'heure). */
    public record NoteRequest(@NotBlank @Size(max = 500) String note) {}

    /**
     * Ce que l'atelier ajoute quand il déclare la sortie en qualité. Les quatre cases
     * sont les siennes : le serveur ne remplit ni une criticité ni une action
     * corrective qu'un humain n'a pas écrites.
     *
     * Le reste du dossier — le code défaut, la pièce en cause, le bus, le client,
     * l'homme — se relit sur la sortie, et c'est pour ça que cette déclaration peut
     * se faire en un clic sans ressaisie.
     */
    public record DeclarationQualiteRequest(@Size(max = 120) String classification,
                                            @Size(max = 20) String severity,
                                            @Size(max = 2000) String correctiveActions,
                                            @Size(max = 2000) String preventiveActions) {}

    /**
     * Ce que la sortie vient de faire naître. Le numéro du dossier se rend ici parce
     * que l'atelier doit pouvoir le relire après coup : sans lui, le clic produit un
     * dossier que personne ne sait retrouver.
     */
    public record DeclarationQualiteResponse(String dossierId,
                                             String dossierNumber,
                                             String title,
                                             String declaredAt,
                                             String sortieNumber) {}

    /**
     * Un homme et ce qui l'empêche de partir. L'écran d'affectation ne peut pas
     * proposer quelqu'un pour refuser le choix juste après : le chef perd son clic,
     * et une notification qu'il ne lit pas n'est pas un refus. La liste se fait
     * serveur, parce que c'est le serveur qui sait qui est dehors.
     */
    public record CrewMemberOption(String userId, String name, String technicianId,
                                   String sortieNumber, String sortieStatus, String city) {}

    /** Les libres d'abord — le select ne montre qu'eux ; les dehors sont écrits en dur dessous. */
    public record CrewResponse(List<CrewMemberOption> free, List<CrewMemberOption> out) {}

    /**
     * Rectification d'une heure ou d'un kilométrage déjà enregistré. Le champ
     * visé et la raison sont obligatoires : la correction s'ajoute au journal,
     * elle n'efface pas la valeur de départ.
     */
    public record CorrectionRequest(@NotBlank String field,
                                    LocalDateTime value,
                                    Long mileageValue,
                                    @NotBlank @Size(max = 500) String reason) {}

    public record LocationDto(String address, String city) {}

    /** Les huit heures d'étape, telles qu'écrites par le serveur. */
    public record TimesDto(LocalDateTime reportedAt, LocalDateTime dispatchedAt,
                           LocalDateTime departedAt, LocalDateTime arrivedAt,
                           LocalDateTime finishedAt, LocalDateTime leftSiteAt,
                           LocalDateTime returnedAt, LocalDateTime closedAt) {}

    public record MileagesDto(Long startMileage, Long endMileage, Long busMileage,
                              Long busMileageEnd, Long distanceKm) {}

    /** Durées déduites des horodatages — jamais saisies. */
    public record DurationsDto(Long travelMinutes, Long onSiteMinutes,
                               Long siteMinutes, Long totalMinutes) {}

    /**
     * Réponse d'une sortie. Le libellé du dossier rattaché (et pas seulement son
     * identifiant) est joint : à l'écran comme sur le bon PDF, « SAV-WAR-2026-004 »
     * se contrôle d'un coup d'œil, un UUID non.
     *
     * {@code purpose} dit ce que la sortie est allée faire — « panne » ou « entretien »
     * — et les trois identifiants qui suivent ne sont remplis que dans le second cas :
     * le forfait et le palier engagés, puis l'opération créée par la clôture. Le
     * libellé du palier n'est pas dupliqué ici : la fiche du forfait le porte déjà, et
     * deux copies du même libellé finissent par se contredire quand la grille change.
     *
     * {@code missionNumber} et {@code missionDecidedAt} sont relus sur la mission qui a
     * fait sortir cette voiture : c'est la mission qui porte l'identifiant de sa sortie,
     * jamais l'inverse. Rien n'est recopié dans la sortie — une colonne de plus dans
     * les deux tables ferait deux vérités à tenir à jour.
     */
    public record InterventionResponse(String id, String number, String status, String priority,
                                       String vehicleId, String vehicleLabel,
                                       String clientId, String clientName,
                                       String technicianId, String technicianName,
                                       String serviceVehicleId, String serviceVehicleRegistration,
                                       LocationDto location, String requestedBy, String description,
                                       TimesDto times, MileagesDto mileages, DurationsDto durations,
                                       String defectCode, String diagnosis, String causingPart,
                                       String resultNote, String receivedBy, String closureNote,
                                       String savCaseId, String savCaseNumber,
                                       String purpose, String soldPlanId, String planStepId,
                                       String maintenanceItemId,
                                       String createdBy,
                                       boolean runningLate, LocalDateTime referenceTime,
                                       LocalDateTime createdAt, LocalDateTime updatedAt,
                                       String missionNumber, LocalDateTime missionDecidedAt,
                                       List<ChecklistItemResponse> checklist,
                                       boolean checklistIncomplete) {}

    public record InterventionEventResponse(String eventType, String source, String actorEmail,
                                            LocalDateTime recordedAt, String note) {

        public static InterventionEventResponse from(
                tn.higer.sav_backend.model.InterventionEvent e) {
            return new InterventionEventResponse(
                    e.getEventType() != null ? e.getEventType().name().toLowerCase() : null,
                    e.getSource() != null ? e.getSource().name().toLowerCase() : null,
                    e.getActorEmail(), e.getRecordedAt(), e.getNote());
        }
    }

    /** Détail d'une sortie : la fiche plus son journal, en une requête. */
    public record InterventionDetailResponse(InterventionResponse intervention,
                                            List<InterventionEventResponse> events) {}

    /**
     * « Sorties du jour » : la liste des sorties ouvertes et quatre compteurs.
     * Une sortie non rentrée après X heures est un signal, pas une faute :
     * le champ longSorties le compte, un humain décide ensuite.
     *
     * {@code enRouteCount} se lit sur les horodatages et non sur le statut : « partie »
     * veut dire sortie de l'atelier et pas encore arrivée, ce sont les deux heures
     * posées par l'homme dehors qui le disent. Le compteur du bandeau et la liste
     * viennent du même lecteur, pour ne jamais annoncer un chiffre que l'écran
     * d'à côté ne montre pas.
     */
    public record DayBoardResponse(List<InterventionResponse> sorties,
                                   long openCount, long enRouteCount,
                                   long onSiteCount, long longRunningCount,
                                   long vehiclesEngaged) {}
}
