package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.CallerChannel;
import tn.higer.sav_backend.model.IncidentNature;
import tn.higer.sav_backend.model.PartOrderState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SavPriority;
import tn.higer.sav_backend.model.VisitState;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class SavCaseDtos {

    private SavCaseDtos() {}

    /**
     * Création / modification d'un dossier SAV (admin et gestionnaire_sav).
     * Les valeurs énumérées arrivent en minuscules depuis Angular
     * (« visites », « in_progress ») — accept-case-insensitive-enums est activé.
     *
     * « caseNumber » est laissé vide par l'écran : le serveur le donne
     * (SAV-WAR-2026-001). Un numéro inventé dans le navigateur peut être tapé deux
     * fois ou écrasé, et c'est justement la référence du dossier Higer.
     *
     * « parentCaseId » n'est pas dans cette requête : un lien de filiation ne se
     * décrète pas depuis un navigateur, il se crée par la porte du serveur
     * (POST /{id}/open-claim). Sinon n'importe quel poste pourrait réécrire
     * l'histoire d'un dossier.
     *
     * « partReference » et « quantity » ne sont plus lus : une pièce posée est
     * désormais une ligne nommée (V21), rattachée au papier qui l'a montée. Les
     * colonnes restent en base pour les dossiers d'avant.
     */
    public record SavCaseRequest(String caseNumber,
                                 @NotNull SavCaseType type,
                                 @NotBlank String title,
                                 @NotBlank String vehicleId,
                                 String clientId,
                                 SavCaseStatus status,
                                 SavPriority priority,
                                 LocalDate createdAt,
                                 LocalDate updatedAt,
                                 @NotBlank String description,
                                 String resolution,
                                 /**
                                  * Le compte de la personne qui suit le dossier (table `users`),
                                  * choisi dans la liste des techniciens — plus un nom tapé à la main.
                                  * Null = l'atelier garde la main ; si des sorties de dépannage sont
                                  * rattachées au dossier, c'est leur technicien qui gagne, et le
                                  * serveur le refuse autrement.
                                  */
                                 UUID technicianUserId,
                                 /**
                                  * « Un technicien doit aller sur place ». Coché, le serveur
                                  * déclare la sortie de dépannage dans la foulée : le bus, le
                                  * client, le lieu et la panne sont déjà dans le dossier, et les
                                  * redemander à l'atelier écrirait deux versions de la même panne.
                                  */
                                 boolean requiresIntervention,
                                 String warrantyApprovalNumber,
                                 Double costEstimate,
                                 /** La demande du client, telle qu'elle est arrivée. */
                                 String callerName,
                                 CallerChannel callerChannel,
                                 LocalDateTime calledAt,
                                 String expectedAction,
                                 /**
                                  * Les cases de l'onglet « Visites clients » du papier de
                                  * référence. Le compte rendu n'en est pas : c'est
                                  * « resolution ». Le serveur refuse ces quatre cases sur
                                  * une autre famille qu'« visites ».
                                  */
                                 LocalDate plannedDate,
                                 LocalDate visitDate,
                                 VisitState visitState,
                                 String nextActions,
                                 /**
                                  * La visite périodique que ce dossier est en train de
                                  * faire : le forfait vendu et son palier. Sans ces deux
                                  * cases, « visite périodique » voulait dire deux choses
                                  * qui ne se connaissaient pas — le rappel sorti du
                                  * compteur du bus et le papier tapé à l'atelier. Le
                                  * serveur vérifie les deux (le palier est bien un palier
                                  * de CE forfait, vendu sur CE bus) et les refuse hors
                                  * famille « visites ».
                                  */
                                 String soldPlanId,
                                 String planStepId,
                                 /**
                                  * Les cases de l'onglet « Qualité » : la date de déclaration,
                                  * la classification, la criticité, puis le correctif et le
                                  * préventif. L'anomalie est « description », et « Clos » est
                                  * le statut du dossier. Refusé hors famille « qualite ».
                                  */
                                 LocalDate declarationDate,
                                 String classification,
                                 String severity,
                                 String correctiveActions,
                                 String preventiveActions,
                                 /**
                                  * Le bloc « Pièces de rechange » du papier de référence, et
                                  * le papier AG. Deux objets à part, posés sur la requête :
                                  * l'un porte trente cases, l'autre huit, et les étaler dans
                                  * une liste de paramètres ne dirait plus à quoi elles
                                  * servent.
                                  *
                                  * Absent (null) = « je ne dis rien de ce bloc », et le
                                  * serveur ne touchera à rien : l'écran Dépannage modifie un
                                  * dossier sans rien savoir du papier. Présent = le bloc est
                                  * écrit tel quel, blanc compris — un poste qui efface une
                                  * case l'efface vraiment.
                                  */
                                 PartOrderRequest order,
                                 AgPaperRequest agPaper) {}

    /**
     * Une commande de pièces, telle que l'onglet du papier de référence la tient.
     * Les deux quantités sont séparées : « partielle » se lit dans leur écart.
     */
    public record PartOrderRequest(LocalDate orderDate,
                                   String reference,
                                   String designation,
                                   Integer quantityOrdered,
                                   Integer quantityReceived,
                                   PartOrderState state,
                                   LocalDate deliveryDate,
                                   String comments) {}

    /**
     * Le papier d'appel en garantie, dans ses cases qui n'existaient pas encore.
     * Ce que le dossier porte déjà sous un autre nom n'y est pas — il se relit à
     * sa place (plainte = description, correction = resolution, coût demandé =
     * claimAmountClaimed, décision = claimDecision, pièces = lignes posées,
     * véhicule = fiche du bus, clôture = closedAt).
     *
     * Aucun montant n'est calculé par le serveur : les trois cases
     * main-d'œuvre sont recopiées du papier, et le barème de remboursement du
     * constructeur n'a jamais été fourni.
     */
    public record AgPaperRequest(String writerName,
                                 String repairOrderNumber,
                                 LocalDate repairOrderDate,
                                 LocalDate incidentDate,
                                 String importer,
                                 String dealerNumber,
                                 String unitNumber,
                                 String camNumber,
                                 Integer km,
                                 Boolean securityImpact,
                                 IncidentNature incidentType,
                                 String concernedPart,
                                 String partNewOrReconditioned,
                                 Integer organKm,
                                 String previousClaimNumber,
                                 String customerContext,
                                 String cause,
                                 String comments,
                                 String laborOperationCode,
                                 String laborDescription,
                                 Double allocatedTime,
                                 Double hourlyCost,
                                 Double laborNet,
                                 String causalPartReference,
                                 String causalPartDescription,
                                 String functionGroup,
                                 String serialNumber,
                                 String defectType,
                                 String defectCode,
                                 Double otherCosts) {}

    /**
     * La décision du constructeur sur un appel en garantie. Tenu À PART du statut du
     * dossier : le statut dit où en est le travail, ceci dit ce que le constructeur
     * a répondu. Les deux montants sont saisis par un humain — le portail ne les
     * calcule pas, parce que le barème de remboursement n'a jamais été fourni.
     */
    public record ClaimRequest(ClaimState state,
                               String decision,
                               LocalDate decisionAt,
                               Double amountClaimed,
                               Double amountGranted) {}

    public record SavCaseResponse(String id, String caseNumber, String type, String title,
                                  /**
                                   * Le bus et le client, nommés par le serveur. La clé seule ne
                                   * suffisait pas à l'écran : pour écrire « 12345 329 00 — SNTRI »
                                   * sur une ligne de dossier, le poste devait ouvrir
                                   * /api/vehicles et /api/clients, deux listes que seuls les rôles
                                   * qui en ont besoin ont le droit de voir. Un nom est une lecture
                                   * de la fiche, pas une donnée du dossier : il voyage avec la vue,
                                   * la fiche reste derrière sa porte.
                                   */
                                  String vehicleId, String vehicleLabel, String vehicleVin,
                                  String vehicleRegistration, String vehicleModel,
                                  String clientId, String clientName,
                                  String status, String priority,
                                  LocalDate createdAt, LocalDate updatedAt, String description,
                                  String resolution, String technicianUserId, String technician,
                                  String warrantyApprovalNumber,
                                  String partReference, Integer quantity, Double costEstimate,
                                  /** Le n° de la sortie née du dossier, quand elle est encore en cours. */
                                  String sortieNumber,
                                  /**
                                   * Le n° de la demande d'intervention que le dossier a fait naître
                                   * (MT-). Null quand la case n'a jamais été cochée. C'est la preuve
                                   * que le geste a été pris en compte : sans numéro à l'écran, le
                                   * poste croit n'avoir rien fait.
                                   */
                                  String missionNumber,
                                  /** Le dossier dont celui-ci est né, et son numéro. */
                                  String parentCaseId, String parentCaseNumber,
                                  /** Les dossiers enfants, pour que la ligne le dise sans recharger. */
                                  List<SavCaseLinkOption> children,
                                  String callerName, String callerChannel, String callerChannelLabel,
                                  LocalDateTime calledAt, String expectedAction,
                                  String claimState, String claimStateLabel, String claimDecision,
                                  LocalDate claimDecisionAt, Double claimAmountClaimed,
                                  Double claimAmountGranted,
                                  /**
                                   * « Ce dossier garantie est clôturé et rien n'a été demandé au
                                   * constructeur. » Calculé à la lecture, jamais écrit : le portail
                                   * signale, un humain décide.
                                   */
                                  boolean claimADeposer,
                                  /** L'onglet « Visites clients » : la visite a son propre état. */
                                  LocalDate plannedDate, LocalDate visitDate,
                                  String visitState, String visitStateLabel, String nextActions,
                                  /** La visite périodique rattachée : le forfait vendu et son palier. */
                                  String soldPlanId, String planStepId,
                                  /** L'onglet « Qualité » : la déclaration et ses deux actions. */
                                  LocalDate declarationDate, String classification, String severity,
                                  String correctiveActions, String preventiveActions,
                                  /** Le jour où le travail s'est arrêté, écrit par le serveur seul. */
                                  LocalDate closedAt,
                                  /** Le bloc « pièces de rechange » : null hors de cette famille. */
                                  PartOrderResponse order,
                                  /** Le papier AG : null hors de la famille garanties. */
                                  AgPaperResponse agPaper) {

        /**
         * La vue d'un dossier relu seul — une validation, un refus. Les noms du bus et
         * du client viennent du serveur, jamais du navigateur : une vue sans nom
         * rendrait un bus « N/A » sur un écran qui a le droit de le nommer.
         */
        public static SavCaseResponse from(SavCase c, FicheLue fiche) {
            return from(c, null, null, List.of(), null, fiche);
        }

        /** La sortie en cours ET la demande née du dossier : les deux numéros se montrent au guichet. */
        public static SavCaseResponse from(SavCase c, String sortieNumber, String missionNumber,
                                           FicheLue fiche) {
            return from(c, sortieNumber, missionNumber, List.of(), null, fiche);
        }

        public static SavCaseResponse from(SavCase c, String sortieNumber, List<SavCase> enfants,
                                           String parentCaseNumber, FicheLue fiche) {
            return from(c, sortieNumber, null, enfants, parentCaseNumber, fiche);
        }

        public static SavCaseResponse from(SavCase c, String sortieNumber, String missionNumber,
                                          List<SavCase> enfants, String parentCaseNumber,
                                          FicheLue fiche) {
            return new SavCaseResponse(
                    c.getId(), c.getCaseNumber(),
                    c.getType() != null ? c.getType().name().toLowerCase() : null,
                    c.getTitle(), c.getVehicleId(), fiche.busLabel(), fiche.busVin(),
                    fiche.busImmatriculation(), fiche.busModele(),
                    c.getClientId(), fiche.clientName(),
                    c.getStatus() != null ? c.getStatus().name().toLowerCase() : null,
                    c.getPriority() != null ? c.getPriority().name().toLowerCase() : null,
                    c.getCreatedAt(), c.getUpdatedAt(), c.getDescription(),
                    c.getResolution(),
                    c.getTechnicianUserId() != null ? c.getTechnicianUserId().toString() : null,
                    c.getTechnician(), c.getWarrantyApprovalNumber(),
                    c.getPartReference(), c.getQuantity(), c.getCostEstimate(), sortieNumber,
                    missionNumber,
                    c.getParentCaseId(), parentCaseNumber,
                    enfants.stream().map(SavCaseLinkOption::from).toList(),
                    c.getCallerName(),
                    c.getCallerChannel() != null ? c.getCallerChannel().name().toLowerCase() : null,
                    c.getCallerChannel() != null ? c.getCallerChannel().label() : null,
                    c.getCalledAt(), c.getExpectedAction(),
                    c.getClaimState() != null ? c.getClaimState().name().toLowerCase() : null,
                    c.getClaimState() != null ? c.getClaimState().label() : null,
                    c.getClaimDecision(), c.getClaimDecisionAt(),
                    c.getClaimAmountClaimed(), c.getClaimAmountGranted(),
                    estALaisser(c),
                    c.getPlannedDate(), c.getVisitDate(),
                    c.getVisitState() != null ? c.getVisitState().name().toLowerCase() : null,
                    c.getVisitState() != null ? c.getVisitState().label() : null,
                    c.getNextActions(),
                    c.getType() == SavCaseType.VISITES ? c.getSoldPlanId() : null,
                    c.getType() == SavCaseType.VISITES ? c.getPlanStepId() : null,
                    c.getDeclarationDate(), c.getClassification(), c.getSeverity(),
                    c.getCorrectiveActions(), c.getPreventiveActions(),
                    c.getClosedAt(),
                    c.getType() == SavCaseType.PIECES ? PartOrderResponse.from(c) : null,
                    c.getType() == SavCaseType.GARANTIES ? AgPaperResponse.from(c) : null);
        }

        /** Famille garantie, travail fini, aucune demande déposée : ça se signale. */
        private static boolean estALaisser(SavCase c) {
            return c.getType() == SavCaseType.GARANTIES
                    && c.getStatus() == SavCaseStatus.CLOSED
                    && c.getClaimState() == null;
        }
    }

    /**
     * La fiche du bus et celle du client, réduites aux mots que l'écran Dossiers doit
     * poser sur une ligne : l'identité du bus (son VIN, sa plaque à défaut), son
     * modèle, le nom du client. Portées par la vue, elles évitent au poste d'ouvrir
     * les deux listes du portefeuille pour lire un dossier — et elles évitent au
     * portail d'avoir à choisir, selon le rôle, entre « N/A » et une autorisation de
     * trop. Vide quand la fiche ne dit rien : une case absente se voit, pas une chaîne
     * blanche.
     */
    public record FicheLue(String busLabel, String busVin, String busImmatriculation,
                           String busModele, String clientName) {

        /** Le dossier n'a ni bus ni client nommé : tout se rend vide, la vue reste lisible. */
        public static final FicheLue AUCUNE = new FicheLue(null, null, null, null, null);
    }

    /**
     * La commande de pièces relue, avec le mot français de son état — c'est le
     * serveur qui le connaît, pas une table de traduction dans le navigateur.
     */
    public record PartOrderResponse(LocalDate orderDate, String reference, String designation,
                                    Integer quantityOrdered, Integer quantityReceived,
                                    String state, String stateLabel, LocalDate deliveryDate,
                                    String comments) {

        public static PartOrderResponse from(SavCase c) {
            return new PartOrderResponse(c.getOrderDate(), c.getOrderReference(), c.getOrderDesignation(),
                    c.getQuantityOrdered(), c.getQuantityReceived(),
                    c.getOrderState() != null ? c.getOrderState().name().toLowerCase() : null,
                    c.getOrderState() != null ? c.getOrderState().label() : null,
                    c.getDeliveryDate(), c.getOrderComments());
        }
    }

    /** Le papier AG relu, case par case, avec le mot français de la nature d'incident. */
    public record AgPaperResponse(String writerName, String repairOrderNumber,
                                  LocalDate repairOrderDate, LocalDate incidentDate,
                                  String importer, String dealerNumber, String unitNumber,
                                  String camNumber, Integer km, Boolean securityImpact,
                                  String incidentType, String incidentTypeLabel,
                                  String concernedPart, String partNewOrReconditioned,
                                  Integer organKm, String previousClaimNumber,
                                  String customerContext, String cause, String comments,
                                  String laborOperationCode, String laborDescription,
                                  Double allocatedTime, Double hourlyCost, Double laborNet,
                                  String causalPartReference, String causalPartDescription,
                                  String functionGroup, String serialNumber, String defectType,
                                  String defectCode, Double otherCosts) {

        public static AgPaperResponse from(SavCase c) {
            return new AgPaperResponse(c.getAgWriterName(), c.getAgRepairOrderNumber(),
                    c.getAgRepairOrderDate(), c.getAgIncidentDate(),
                    c.getAgImporter(), c.getAgDealerNumber(), c.getAgUnitNumber(), c.getAgCamNumber(),
                    c.getAgKm(), c.getAgSecurityImpact(),
                    c.getAgIncidentType() != null ? c.getAgIncidentType().name().toLowerCase() : null,
                    c.getAgIncidentType() != null ? c.getAgIncidentType().label() : null,
                    c.getAgConcernedPart(), c.getAgPartNewOrReconditioned(),
                    c.getAgOrganKm(), c.getAgPreviousClaimNumber(),
                    c.getAgCustomerContext(), c.getAgCause(), c.getAgComments(),
                    c.getAgLaborOperationCode(), c.getAgLaborDescription(),
                    c.getAgAllocatedTime(), c.getAgHourlyCost(), c.getAgLaborNet(),
                    c.getAgCausalPartReference(), c.getAgCausalPartDescription(),
                    c.getAgFunctionGroup(), c.getAgSerialNumber(), c.getAgDefectType(),
                    c.getAgDefectCode(), c.getAgOtherCosts());
        }
    }

    /**
     * Une sortie qui répond à l'appel en garantie, réduite à ce que le constructeur
     * regarde : son numéro (la preuve que quelqu'un est allé voir le bus), si le
     * travail est fini, le compteur relevé devant le bus, le code défaut lu et la
     * pièce mise en cause.
     *
     * Le compteur se recopie, il ne se calcule pas : {@code kmReleve} est la valeur
     * que l'homme a lue sur le tableau de bord, et le papier AG la reprend telle
     * quelle. Le portail n'invente pas un kilométrage entre deux relevés.
     */
    public record AgSortieJointe(String number, String status, boolean terminee,
                                 LocalDateTime reportedAt,
                                 LocalDateTime closedAt, Long kmReleve,
                                 Long kmReleveApres, String defectCode, String causingPart,
                                 String atelierPaperNumber) {}

    /**
     * Une pièce que l'appel en garantie réclame au constructeur.
     *
     * Deux maisons, un seul mot à l'écran : la pièce montée pendant un papier
     * d'atelier ({@code item_parts}, V21) et la pièce montée par une sortie de
     * dépannage ({@code intervention_part_requests}, V31) sont la même marchandise
     * pour Higer. Leurs cases ne se recouvrent pas, et rien n'est deviné pour les
     * remplir : {@code remonteeLe} reste vide tant que la pose n'a pas eu lieu, et
     * le sort du retour ne s'écrit que là où le portail sait l'écrire.
     *
     * Aucun prix, aucun total : une ligne de pièce ne chiffre rien ici, le barème de
     * remboursement du constructeur n'ayant jamais été fourni.
     */
    public record AgPieceJointe(String reference, String designation, Double quantite,
                                LocalDate monteeLe, String sortieNumber,
                                String atelierPaperNumber, String etat, String etatLabel,
                                Boolean retourEnvoye, LocalDate retourLe, String motifDeNonRetour) {}

    /**
     * Le dossier garantie vu du côté du constructeur : ce qu'il trouvera dans le
     * papier (ses sorties, ses pièces), ce qui manque encore pour le déposer
     * ({@code manquants}), et ce qui se lit mais ne se remplit pas
     * ({@code signes}).
     *
     * {@code pret} veut dire une seule chose : plus aucune case du dossier n'attend
     * d'être écrite. Ce n'est pas le portail qui dépose l'appel — un dossier complet
     * reste une décision humaine.
     */
    public record AgPapierJustifie(String caseNumber, List<AgSortieJointe> sorties,
                                   List<AgPieceJointe> pieces, List<String> manquants,
                                   List<String> signes, boolean pret) {}

    /**
     * Ligne allégée pour le choix d'un dossier à rattacher à une sortie de
     * dépannage : quatre champs suffisent pour reconnaître le bon dossier, la
     * liste complète arriverait lourde dans un menu déroulant.
     */
    public record SavCaseLinkOption(String id, String caseNumber, String title,
                                    String type, String status) {

        public static SavCaseLinkOption from(SavCase c) {
            return new SavCaseLinkOption(c.getId(), c.getCaseNumber(), c.getTitle(),
                    c.getType() != null ? c.getType().name().toLowerCase() : null,
                    c.getStatus() != null ? c.getStatus().name().toLowerCase() : null);
        }
    }
}
