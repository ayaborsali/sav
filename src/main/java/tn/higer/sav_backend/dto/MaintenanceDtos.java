package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tn.higer.sav_backend.model.MaintenanceAttachment;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class MaintenanceDtos {

    private MaintenanceDtos() {}

    /**
     * Une ligne du papier. {@code nature} et {@code suppliedBy} sont les deux mots que
     * le devis exige (main-d'œuvre / pièce / déplacement, et qui fournit la pièce) ;
     * {@code natureLabel} et {@code suppliedByLabel} portent le mot français rendu tel
     * quel à l'écran. Une ligne de catalogue n'a pas de nature : la case est vide, et
     * ce n'est pas un défaut — c'est {@code DevisService} qui la réclame, sur un devis.
     *
     * {@code done} et {@code realHours} sont les deux cases que l'ordre de travail
     * exige à la clôture : l'opération a-t-elle été faite, et combien d'heures elle a
     * pris. La colonne « Prévu » de la maquette ne demande pas de case neuve : c'est
     * {@code quantityText}, le temps standard écrit par le catalogue (« 0,9 h »).
     */
    public record MaintenanceLineDto(String reference, String label, Double quantity, Double unitPrice,
                                     String quantityText, String nature, String suppliedBy,
                                     String natureLabel, String suppliedByLabel,
                                     Boolean done, Double realHours,
                                     /**
                                      * La pièce du catalogue dont cette ligne est la copie (V46).
                                      * Nulle pour une ligne tapée à la main. Le nom de la
                                      * composante est le contrat avec l'écran : l'interface
                                      * TypeScript `MaintenanceItem.details` lit `pieceId`, et
                                      * `maintenance-devis-pieces.spec.ts` le fige sur un littéral
                                      * brut pour qu'un renommage d'un seul côté ne rende pas une
                                      * ligne muette chez l'atelier.
                                      */
                                     String pieceId) {}

    /**
     * Création / modification d'un document de maintenance (devis, bon de livraison, ordre de travail).
     * Les valeurs énumérées arrivent en minuscules depuis Angular
     * (« devis », « in_progress ») — accept-case-insensitive-enums est activé.
     *
     * {@code number} peut rester vide : le serveur pose alors le numéro de la famille
     * (DV-, OR-, BL-) sur la suite de l'année. Un poste qui en tape un garde la main
     * sur les papiers d'avant, mais deux compteurs dans la même table donnent tôt ou
     * tard deux papiers du même numéro — et un numéro posé deux fois est une facture
     * refusée à l'impression.
     *
     * {@code soldPlanId} et {@code planStepId} sont facultatifs : un document hors
     * forfait reste le cas normal et le plus fréquent. Quand ils sont remplis, ils
     * sont vérifiés côté serveur (le bus du document est celui du forfait, le
     * palier appartient à ce forfait) et le suivi du palier avance avec le statut
     * du document.
     *
     * {@code totalHT} et {@code totalTTC} sont reçus et ignorés : les totaux sont
     * calculés par le serveur à partir des lignes.
     *
     * {@code anomalies} est la case de l'ordre de travail : ce qui a été vu sur le bus
     * pendant le travail. Un devis et un bon de livraison n'en ont pas — le serveur le
     * dit plutôt que de le ranger dans une case que l'écran ne relit jamais.
     *
     * Sur la réponse, {@code missionId} et {@code sourceItemId} sont les deux liens
     * d'origine (V35) : la demande qui a fait ouvrir le papier, et le papier dont il
     * est né. Ils s'écrivent au clic qui crée le suivant — « Créer l'ordre » sur un
     * devis, « Éditer le bon de livraison » sur un ordre — jamais au clavier : un
     * lien tapé à la main n'est pas une preuve d'origine. {@code typeLabel} et
     * {@code statusLabel} portent le mot français de l'enum, rendu tel quel : une
     * pastille « delivered » ne dit pas à l'atelier si le bus est rendu.
     *
     * Les quatre dernières cases ({@code sentOn}, {@code acceptedOn},
     * {@code refusedOn}, {@code refusalReason}) sont l'accord du client d'un devis, en
     * lecture seule : elles ne s'écrivent que par « Envoyer au client », « Enregistrer
     * l'accord » ou « Enregistrer le refus ». Une date d'accord tapée à la main se
     * recule quand le prix dérange.
     *
     * {@code mileageAtDelivery} et {@code partsReturned} sont les deux cases du bon de
     * livraison : le compteur du bus à la restitution et la remise des pièces remplacées.
     * Le compteur n'est pas une copie décorative de la fiche du bus — il rejoint
     * {@code vehicles.mileage} par la porte unique ({@code VehicleMileageService}), qui
     * refuse une baisse sans motif écrit. Sur un devis ou un ordre, ces deux cases
     * n'existent pas : le serveur le dit au lieu de les ranger dans une colonne que
     * l'écran ne relit jamais.
     */
    public record MaintenanceRequest(@NotNull MaintenanceType type,
                                     String number,
                                     @NotBlank String vehicleId,
                                     String clientId,
                                     LocalDate date,
                                     MaintenanceStatus status,
                                     Double totalHT,
                                     Double totalTTC,
                                     String technician,
                                     String description,
                                     String anomalies,
                                     String soldPlanId,
                                     String planStepId,
                                     List<MaintenanceLineDto> details,
                                     Long mileageAtDelivery,
                                     Boolean partsReturned) {}

    /**
     * Le papier rendu à l'écran.
     *
     * {@code statusLabel} est le mot que l'atelier lit, tel que le serveur l'écrit —
     * et pour un ordre de travail c'est « À faire » là où un devis lit « Brouillon »
     * ({@code MaintenanceStatus.label(MaintenanceType)}). Une même valeur de colonne
     * ne se dit pas de la même façon d'un bout à l'autre de la chaîne.
     *
     * Les trois cases qui précèdent sont la clôture de l'ordre : la date du clic
     * ({@code closedOn}), ce qui a été vu sur le bus ({@code anomalies}), et la raison
     * écrite qui a tenu lieu de preuve ({@code closureReason}). Elles ne s'écrivent que
     * par « Clôturer l'ordre », jamais par le formulaire.
     *
     * Les six d'après sont la réception du bus sur le bon de livraison : qui a signé et
     * à quel titre, ce qu'il a réservé, si les pièces remplacées sont reparties avec lui,
     * le compteur lu à la restitution, et le jour du clic. {@code signedOn} ne se tape
     * pas — « Marquer signé » l'écrit, et le nom du signataire avec lui. Un bon de
     * livraison signé se relit tel quel : le client l'a eu en main.
     */
    public record MaintenanceResponse(String id, String type, String number, String vehicleId,
                                      String clientId, LocalDate date, String status,
                                      Double totalHT, Double totalTTC, String technician,
                                      String description, String soldPlanId, String planStepId,
                                      List<MaintenanceLineDto> details,
                                      String missionId, String sourceItemId,
                                      String typeLabel, String statusLabel,
                                      LocalDate sentOn, LocalDate acceptedOn, LocalDate refusedOn,
                                      String refusalReason,
                                      LocalDate closedOn, String anomalies, String closureReason,
                                      String signataireNom, String signataireQualite, LocalDate signedOn,
                                      String reserves, Boolean partsReturned, Long mileageAtDelivery) {

        public static MaintenanceResponse from(MaintenanceItem m) {
            return new MaintenanceResponse(
                    m.getId(),
                    m.getType() != null ? m.getType().name().toLowerCase() : null,
                    m.getNumber(), m.getVehicleId(), m.getClientId(), m.getDate(),
                    m.getStatus() != null ? m.getStatus().name().toLowerCase() : null,
                    m.getTotalHT(), m.getTotalTTC(), m.getTechnician(), m.getDescription(),
                    m.getSoldPlanId(), m.getPlanStepId(),
                    m.getDetails().stream()
                            .map(MaintenanceResponse::line).toList(),
                    m.getMissionId(), m.getSourceItemId(),
                    m.getType() == null ? null : m.getType().label(),
                    m.getStatus() == null ? null : m.getStatus().label(m.getType()),
                    m.getSentOn(), m.getAcceptedOn(), m.getRefusedOn(), m.getRefusalReason(),
                    m.getClosedOn(), m.getAnomalies(), m.getClosureReason(),
                    m.getSignataireNom(), m.getSignataireQualite(), m.getSignedOn(), m.getReserves(),
                    m.getPartsReturned(), m.getMileageAtDelivery());
        }

        private static MaintenanceLineDto line(MaintenanceDetail d) {
            return new MaintenanceLineDto(d.getReference(), d.getLabel(), d.getQuantity(),
                    d.getUnitPrice(), d.getQuantityText(),
                    d.getNature() == null ? null : d.getNature().name().toLowerCase(),
                    d.getSuppliedBy() == null ? null : d.getSuppliedBy().name().toLowerCase(),
                    d.getNature() == null ? null : d.getNature().label(),
                    d.getSuppliedBy() == null ? null : d.getSuppliedBy().label(),
                    d.getDone(), d.getRealHours(), d.getPieceId());
        }
    }

    /**
     * Le motif qui remplace la preuve photo à la clôture de l'ordre — la même porte que
     * le « sans photo » d'une sortie : un humain peut clôturer sans cliché, jamais sans
     * l'écrire.
     */
    public record ClotureRequest(String motif) {}

    /** Le motif que le client donne quand il ne signe pas — obligatoire, et écrit sur le papier. */
    public record RefusRequest(String motif) {}

    /**
     * La réception du bus, telle que « Marquer signé » l'écrit sur le bon de livraison.
     *
     * {@code signataireNom} est exigé : le portail n'a pas de compte client, donc la
     * seule trace de qui a repris le bus est ce nom tapé sur le papier.
     * {@code signataireQualite} dit à quel titre il signe — le chauffeur qui émarge ne
     * vaut pas le directeur qui engage. {@code reserves} est ce que le client a constaté
     * en repartant : ce qui est écrit là se relit à la réclamation, ce qui ne l'est pas
     * disparaît avec la mémoire.
     *
     * La date n'est pas dans cette case : c'est le jour du clic, posé par le serveur.
     * Le scan non plus : il se joint au document avant le clic, et sans fichier joint il
     * n'y a pas de signature — un « oui » que personne ne peut montrer se retourne contre
     * l'atelier à la première réserve contestée.
     */
    public record SignatureRequest(String signataireNom, String signataireQualite, String reserves) {}

    /**
     * Une pièce jointe d'un document. Le fichier lui-même ne passe jamais par la
     * base : ce que l'écran reçoit ici est le chemin de service ({@code url}) et
     * l'empreinte, pas les octets.
     *
     * {@code url} est relative et SANS le préfixe {@code /api} — c'est la règle de
     * l'application pour toute adresse rendue par le serveur, les photos de
     * dépannage la suivent déjà.
     */
    public record AttachmentResponse(String id, String maintenanceItemId, String originalName,
                                     String contentType, Long sizeBytes, String sha256,
                                     String uploadedBy, LocalDateTime uploadedAt, String url) {

        public static AttachmentResponse from(MaintenanceAttachment a) {
            return new AttachmentResponse(a.getId(), a.getMaintenanceItemId(), a.getOriginalName(),
                    a.getContentType(), a.getSizeBytes(), a.getSha256(), a.getUploadedBy(),
                    a.getUploadedAt(),
                    "/maintenance/" + a.getMaintenanceItemId() + "/attachments/" + a.getId() + "/content");
        }
    }
}
