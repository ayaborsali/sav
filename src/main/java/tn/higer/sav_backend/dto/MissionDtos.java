package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Les aller-retours de l'écran Missions.
 *
 * La demande du client n'est pas dans ces DTO : elle se lit sur le dossier SAV
 * (V20) et n'est jamais recopiée dans la mission. Ce qui circule ici, c'est la
 * décision et les numéros des papiers qu'elle a faits naître.
 */
public final class MissionDtos {

    private MissionDtos() {}

    /**
     * Enregistrer une demande. Une demande a une origine et une seule : le dossier
     * où un client a parlé ({@code savCaseId}), ou le rappel d'un palier qui a
     * tourné ({@code alertId}) — personne n'a appelé, et le bus comme le client se
     * lisent déjà sur le forfait. Le serveur refuse les deux à la fois comme
     * l'absence des deux : une mission sans origine ne répond à personne.
     */
    public record MissionRequest(String savCaseId, String alertId,
                                 @Size(max = 1000) String note) {}

    /**
     * « Envoyer un homme » sur une mission née d'un rappel : la sortie
     * d'entretien part avec sa voiture et son homme, comme avant. Sur une mission
     * née d'un dossier, les deux cases restent vides — c'est le chef d'atelier qui
     * les remplit sur le volet Sorties, et le serveur ne devine personne.
     */
    public record MissionEnvoiRequest(String serviceVehicleId, String technicianUserId) {}

    /** Le motif écrit d'un refus ou d'un report. Vide = refusé par le serveur. */
    public record MissionReasonRequest(String reason) {}

    /**
     * Une ligne de l'écran. Les cases de l'appel ({@code callerName},
     * {@code callerChannelLabel}, {@code calledAt}) viennent du dossier, à la
     * lecture : ce sont des reflets, pas des colonnes de la mission. Il en va de
     * même pour {@code rappelLabel}, la phrase du rappel quand l'origine est un
     * palier atteint.
     *
     * {@code stateLabel} porte « Soldée » quand le papier du bout de la chaîne est
     * fini — état calculé, jamais stocké.
     *
     * {@code papierLabel} nomme le papier de l'atelier (« ordre de travail » ou
     * « devis ») : les deux vivent dans la même table, et un écran qui dirait
     * « ordre » sur un devis ferait chercher l'atelier un papier qui n'existe pas.
     *
     * La montre ({@code slaState}, {@code slaLabel}) est calculée ici, à la lecture,
     * jamais stockée ni saisie : une heure limite tapée au clavier est une heure
     * qu'aucun événement ne rend vraie. Le départ est l'heure de l'appel, sinon la
     * date où le rappel a tourné ; sans départ, la case reste vide et le dit.
     * {@code slaState} vaut {@code depasse | limite | dans_delai | termine | aucun}.
     *
     * {@code interventionStatus} est rendu tel quel, en minuscules : les mots
     * français d'une sortie appartiennent à l'écran qui les écrit déjà, et une
     * seconde liste de libellés finirait par se contredire avec la première.
     *
     * {@code papiersDeTravail} est la chaîne complète de la demande : un devis
     * accepté puis l'ordre qui en sort tiennent tous les deux sur la ligne, alors
     * que la case unique {@code papierId} ne peut en nommer qu'un (V35). L'ordre de
     * lecture est celui du travail : le premier papier posé en premier.
     */
    public record MissionResponse(String id, String number, String state, String stateLabel,
                                  String issue, String issueLabel,
                                  String savCaseId, String caseNumber, String caseTitle,
                                  String alertId, String rappelLabel,
                                  String clientName, String busLabel,
                                  String busVin, String busRegistration,
                                  String callerName, String callerChannelLabel, String calledAt,
                                  String slaState, String slaLabel,
                                  String interventionId, String interventionNumber,
                                  String interventionStatus, String interventionTechnicianName,
                                  String papierId, String papierNumero, String papierLabel,
                                  String reason, String note,
                                  String decidedBy, String decidedAt, String createdAt,
                                  List<PapierDeTravail> papiersDeTravail) {}

    /**
     * Un papier de l'atelier dans la chaîne d'une demande : son numéro, le mot du
     * papier (devis, ordre de travail, bon de livraison) et son état en français.
     * Les libellés viennent des enums du serveur, jamais d'une liste tenue à
     * l'écran.
     */
    public record PapierDeTravail(String id, String numero, String nature, String etat) {}

    /**
     * Les quatre chiffres que le chef d'atelier regarde en ouvrant l'écran, plus le
     * délai qui les a fait naître. Ils sortent du même lecteur que la liste : deux
     * chiffres qui se contredisent est précisément le défaut reproché au portail.
     *
     * {@code delaiHeures} est la valeur de configuration en force, écrite sur l'écran
     * pour que « 48 h » ne soit pas une légende posée par l'interface.
     */
    public record MissionCountersResponse(long enAttenteDecision, long delaiDepasse,
                                          long depannagesEnCours, long aLAtelier,
                                          long delaiHeures) {}
}
