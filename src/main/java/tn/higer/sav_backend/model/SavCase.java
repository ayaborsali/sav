package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sav_cases")
@Getter @Setter @NoArgsConstructor
public class SavCase {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, unique = true)
    private String caseNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SavCaseType type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String vehicleId;

    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SavCaseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SavPriority priority;

    private LocalDate createdAt;
    private LocalDate updatedAt;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String resolution;

    /**
     * Le compte du technicien qui suit le dossier ({@code users.id}), ou null tant
     * qu'aucune personne n'est rattachée. C'est la seule identité du dossier :
     * {@link #technician} n'est que le nom affiché, recopié au moment du choix.
     */
    @Column(name = "technician_user_id")
    private UUID technicianUserId;

    private String technician;
    private String warrantyApprovalNumber;

    /**
     * Ce que le dossier doit à une réclamation : le dossier de la famille
     * « Réclamation » dont celui-ci est né. Écrit une seule fois, par
     * {@code SavCaseService.openClaimFrom}, et jamais modifiable à l'écran —
     * sinon un poste peut changer l'histoire d'un papier déjà déposé.
     */
    @Column(name = "parent_case_id", length = 50)
    private String parentCaseId;

    /**
     * La demande du client telle qu'elle est arrivée : qui a parlé, par quel
     * canal, à quelle heure, ce qu'il attend. Ces quatre cases sont la seule
     * preuve que l'appel a eu lieu — sans elles, un client qui appelle et n'obtient
     * rien ne laisse aucune trace, et les 48 h du contrat ne se mesurent pas.
     *
     * {@code callerName} est encore du texte libre : la table des contacts du
     * client n'existe pas. Dette déclarée, pas un choix de confort.
     */
    private String callerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "caller_channel", length = 20)
    private CallerChannel callerChannel;

    private LocalDateTime calledAt;

    @Column(columnDefinition = "TEXT")
    private String expectedAction;

    /**
     * La seconde vie du dossier, tenue séparée de {@link #status} : ce qu'en a
     * fait le constructeur. Null sur tout dossier qui n'est pas de la famille
     * GARANTIES — le service le refuse, parce qu'un claim de visite n'a rien à
     * réclamer.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "claim_state", length = 20)
    private ClaimState claimState;

    /** La décision écrite du constructeur, recopiée telle quelle. */
    @Column(name = "claim_decision", columnDefinition = "TEXT")
    private String claimDecision;

    @Column(name = "claim_decision_at")
    private LocalDate claimDecisionAt;

    /**
     * Les deux montants, saisis par un humain et JAMAIS calculés par le serveur :
     * le calcul demanderait le barème de remboursement du constructeur, un chiffre
     * qui n'existe dans aucun document reçu à ce jour. Leur écart est ce que Saphir
     * perd ou gagne sur ce travail.
     */
    @Column(name = "claim_amount_claimed")
    private Double claimAmountClaimed;

    @Column(name = "claim_amount_granted")
    private Double claimAmountGranted;

    private String partReference;

    private Integer quantity;

    private Double costEstimate;

    /**
     * Les cases de l'onglet « Visites clients » du papier de référence. Elles ne
     * vivent que sur un dossier de la famille VISITES — le serveur refuse les
     * autres, parce qu'une date de visite héritée d'une réclamation se relit
     * comme une preuve faux.
     *
     * Le compte rendu n'a pas de colonne ici : c'est {@link #resolution}, la même
     * case sous le même nom d'atelier.
     */
    private LocalDate plannedDate;

    private LocalDate visitDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "visit_state", length = 20)
    private VisitState visitState;

    @Column(columnDefinition = "TEXT")
    private String nextActions;

    /**
     * Le palier du forfait vendu que cette visite couvre, si c'est une visite
     * périodique. Sans ces deux cases, « visite périodique » désignait deux choses
     * qui ne se connaissaient pas : le rappel sorti du compteur d'un côté, le
     * papier de visite de l'autre. Elles ne forcent rien (pas de clé étrangère) et
     * ne vivent que sur la famille VISITES — le serveur refuse ailleurs, comme
     * pour les autres blocs.
     */
    @Column(name = "sold_plan_id", length = 50)
    private String soldPlanId;

    @Column(name = "plan_step_id", length = 50)
    private String planStepId;

    /**
     * Les cases de l'onglet « Qualité » : la date où l'anomalie a été déclarée,
     * sa classification et sa criticité, puis ce qui a été fait pour elle
     * (correctif) et ce qui empêche qu'elle revienne (préventif). L'anomalie
     * elle-même est {@link #description}, et « Clos » est {@link SavCaseStatus#CLOSED}.
     *
     * {@code severity} reste du texte libre et non un enum : la grille de
     * criticité vient d'un document que le constructeur n'a jamais fourni. Les
     * trois mots du papier (Mineure | Majeure | Critique) sont proposés à l'écran,
     * mais un quatrième niveau ne doit pas demander une migration.
     */
    private LocalDate declarationDate;

    private String classification;

    private String severity;

    @Column(columnDefinition = "TEXT")
    private String correctiveActions;

    @Column(columnDefinition = "TEXT")
    private String preventiveActions;

    /**
     * La sortie de dépannage devant laquelle l'anomalie a été vue, quand le dossier
     * qualité est né d'un clic sur sa ligne. Une déclaration part d'un bus : son code
     * défaut, sa pièce en cause, son compteur, son homme — relus ici plutôt que
     * retapés.
     *
     * Ce n'est PAS {@link Intervention#getSavCaseId()} dans l'autre sens : ce
     * pointeur-là dit « cette sortie est le dépannage de ce dossier », et un dossier
     * qualité ne se rembourse pas chez le constructeur. Nullable : quatre familles sur
     * cinq ne naissent d'aucune sortie.
     */
    @Column(name = "declared_from_intervention_id", length = 50)
    private String declaredFromInterventionId;

    /**
     * Le jour où le travail s'est arrêté. Écrit et retiré par le serveur seul,
     * derrière {@link SavCaseStatus#CLOSED} : {@link #updatedAt} change à chaque
     * faute d'orthographe corrigée, et une date de fermeture qui suit le texte
     * n'est plus une preuve de rien.
     */
    @Column(name = "closed_at")
    private LocalDate closedAt;

    /**
     * L'onglet « Pièces de rechange » du papier de référence : une commande, sa
     * référence, ce qu'elle désigne, les deux quantités (demandée / reçue), son
     * état, la date de livraison et les remarques.
     *
     * Deux quantités et non une : « partielle » n'existe que par leur écart. Le
     * bloc ne vit que sur la famille PIECES.
     */
    private LocalDate orderDate;

    @Column(name = "order_reference")
    private String orderReference;

    @Column(name = "order_designation", columnDefinition = "TEXT")
    private String orderDesignation;

    private Integer quantityOrdered;

    private Integer quantityReceived;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_state", length = 20)
    private PartOrderState orderState;

    private LocalDate deliveryDate;

    @Column(name = "order_comments", columnDefinition = "TEXT")
    private String orderComments;

    /**
     * Le papier d'appel en garantie (AG), colonne par colonne, dans la seule
     * mesure où la case n'avait pas encore de maison : la plainte reste
     * {@link #description}, la correction {@link #resolution}, le coût demandé
     * {@link #claimAmountClaimed}, la décision {@link #claimDecision}, les pièces
     * remplacées sont des lignes (V21), le véhicule se lit sur sa fiche, et la
     * date de clôture est {@link #closedAt}.
     *
     * Les codes que le constructeur est seul à détenir (importateur, dealer,
     * unit, CAM, code d'opération, code défaut) restent vides tant qu'il ne les
     * a pas donnés : un numéro inventé sur un papier déposé se retourne contre son
     * auteur. Les montants non plus ne se calculent pas ici — le barème Higer
     * n'existe dans aucun document reçu.
     *
     * Tout ce bloc ne vit que sur la famille GARANTIES.
     */
    @Column(name = "ag_writer_name")
    private String agWriterName;

    @Column(name = "ag_repair_order_number")
    private String agRepairOrderNumber;

    @Column(name = "ag_repair_order_date")
    private LocalDate agRepairOrderDate;

    @Column(name = "ag_incident_date")
    private LocalDate agIncidentDate;

    @Column(name = "ag_importer")
    private String agImporter;

    @Column(name = "ag_dealer_number")
    private String agDealerNumber;

    @Column(name = "ag_unit_number")
    private String agUnitNumber;

    @Column(name = "ag_cam_number")
    private String agCamNumber;

    @Column(name = "ag_km")
    private Integer agKm;

    @Column(name = "ag_security_impact")
    private Boolean agSecurityImpact;

    @Enumerated(EnumType.STRING)
    @Column(name = "ag_incident_type", length = 20)
    private IncidentNature agIncidentType;

    @Column(name = "ag_concerned_part")
    private String agConcernedPart;

    @Column(name = "ag_part_new_or_reconditioned")
    private String agPartNewOrReconditioned;

    @Column(name = "ag_organ_km")
    private Integer agOrganKm;

    @Column(name = "ag_previous_claim_number")
    private String agPreviousClaimNumber;

    @Column(name = "ag_customer_context", columnDefinition = "TEXT")
    private String agCustomerContext;

    @Column(name = "ag_cause", columnDefinition = "TEXT")
    private String agCause;

    @Column(name = "ag_comments", columnDefinition = "TEXT")
    private String agComments;

    @Column(name = "ag_labor_operation_code")
    private String agLaborOperationCode;

    @Column(name = "ag_labor_description", columnDefinition = "TEXT")
    private String agLaborDescription;

    @Column(name = "ag_allocated_time")
    private Double agAllocatedTime;

    @Column(name = "ag_hourly_cost")
    private Double agHourlyCost;

    @Column(name = "ag_labor_net")
    private Double agLaborNet;

    @Column(name = "ag_causal_part_reference")
    private String agCausalPartReference;

    @Column(name = "ag_causal_part_description")
    private String agCausalPartDescription;

    @Column(name = "ag_function_group")
    private String agFunctionGroup;

    @Column(name = "ag_serial_number")
    private String agSerialNumber;

    @Column(name = "ag_defect_type")
    private String agDefectType;

    @Column(name = "ag_defect_code")
    private String agDefectCode;

    @Column(name = "ag_other_costs")
    private Double agOtherCosts;
}
