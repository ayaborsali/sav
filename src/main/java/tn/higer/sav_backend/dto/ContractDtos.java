package tn.higer.sav_backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.Contract;
import tn.higer.sav_backend.model.Vehicle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Contrat de maintenance : en-tête saisi, lignes portées par les véhicules entretenus. */
public final class ContractDtos {

    private ContractDtos() {}

    /**
     * Ligne du contrat. vehicleId désigne un véhicule déjà enregistré ; unitPriceHt,
     * s'il est envoyé, est reporté sur le véhicule (le serveur reste seul maître du prix).
     * C'est ici le forfait HT de la prestation sur ce bus, pas le prix d'achat du bus.
     */
    public record ContractLineRequest(@NotBlank String vehicleId,
                                      @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal unitPriceHt) {}

    /**
     * Le numéro de contrat n'est pas dans cette requête : il est généré par le serveur
     * (CT-HIG-AAAA-NNN). lines null = rattachements inchangés ; liste vide = tous détachés.
     *
     * Les six clauses de rédaction (V5) sont nullables : null = le serveur applique la
     * rédaction standard (ContractClauses). Aucune n'est un montant — les totaux restent
     * calculés à la lecture, jamais saisis.
     */
    public record ContractRequest(@NotNull LocalDate contractDate,
                                  @NotBlank String clientId,
                                  @Size(max = 120) String paymentTerms,
                                  @Size(max = 500) String deliveryAddress,
                                  @Size(max = 1000) String notes,
                                  @Valid List<ContractLineRequest> lines,
                                  @Size(max = 1500) String objectClause,
                                  @Size(max = 1000) String deliveryClause,
                                  @Size(max = 1000) String warrantyClause,
                                  @Size(max = 1000) String ownershipClause,
                                  @Size(max = 1000) String disputeClause,
                                  @Size(max = 2000) String specialTerms) {}

    /** Une ligne telle qu'elle est lue : identité du véhicule + prix convenu. */
    public record ContractLine(String vehicleId, String vin, String registration, String model,
                               Integer year, LocalDate commissioningDate, LocalDate warrantyEndDate,
                               BigDecimal unitPriceHt) {}

    /**
     * Demande de rédaction IA d'une clause. section ∈ {object, delivery, warranty, ownership,
     * dispute, special}. L'IA ne reçoit aucun montant ; elle ne renvoie que du texte de clause.
     */
    public record DraftRequest(@NotBlank String section) {}

    /**
     * Rédaction IA d'un contrat pas encore enregistré : le formulaire est envoyé tel quel, mais
     * uniquement ses identifiants (client, bus cochés). Ni prix, ni total, ni date — le serveur
     * relit lui-même ce qui décrit le dossier. vehicleIds peut être vide : une clause se rédige
     * aussi avant d'avoir coché le premier bus.
     */
    public record DraftPreviewRequest(@NotBlank String section,
                                      @NotBlank String clientId,
                                      @Size(max = 300) List<String> vehicleIds) {}

    /** Proposition de l'IA : le texte + le modèle qui l'a produit (traçabilité dans l'audit). */
    public record DraftResponse(String section, String text, String model) {}

    /** Vue liste : totaux sans le détail des lignes. */
    public record ContractSummary(String id, String number, LocalDate contractDate, String clientId,
                                  String paymentTerms, String deliveryAddress, String notes,
                                  int vehicleCount, BigDecimal totalHT, BigDecimal tva, BigDecimal totalTTC) {}

    /**
     * Vue détail : mêmes champs que la liste, lignes et clauses incluses.
     * Les clauses ne sont pas confidentielles (ce ne sont pas des montants) : elles sortent
     * même en rôle consultation, pour que l'aperçu du contrat reste complet.
     */
    public record ContractResponse(String id, String number, LocalDate contractDate, String clientId,
                                   String paymentTerms, String deliveryAddress, String notes,
                                   int vehicleCount, BigDecimal totalHT, BigDecimal tva, BigDecimal totalTTC,
                                   List<ContractLine> lines,
                                   String objectClause, String deliveryClause, String warrantyClause,
                                   String ownershipClause, String disputeClause, String specialTerms) {}

    /**
     * Totaux recalculés à la lecture à partir des prix des lignes : un contrat stocké
     * ne peut pas porter un total incohérent avec ses véhicules.
     * figures = false (rôle consultation) masque les montants et les conditions de paiement.
     */
    public static ContractSummary toSummary(Contract c, List<Vehicle> lines, boolean figures, BigDecimal tvaRate) {
        Totaux totaux = totaux(lines, tvaRate);
        return new ContractSummary(
                c.getId(), c.getNumber(), c.getContractDate(), c.getClientId(),
                figures ? c.getPaymentTerms() : null, c.getDeliveryAddress(), c.getNotes(),
                lines.size(),
                figures ? totaux.ht() : null,
                figures ? totaux.tva() : null,
                figures ? totaux.ttc() : null);
    }

    public static ContractResponse toResponse(Contract c, List<Vehicle> lines, boolean figures, BigDecimal tvaRate) {
        Totaux totaux = totaux(lines, tvaRate);
        List<ContractLine> detailedLines = lines.stream()
                .map(v -> new ContractLine(v.getId(), v.getVin(), v.getRegistration(), v.getModel(),
                        v.getYear(), v.getCommissioningDate(), v.getWarrantyEndDate(),
                        figures ? v.getUnitPriceHt() : null))
                .toList();
        return new ContractResponse(
                c.getId(), c.getNumber(), c.getContractDate(), c.getClientId(),
                figures ? c.getPaymentTerms() : null, c.getDeliveryAddress(), c.getNotes(),
                lines.size(),
                figures ? totaux.ht() : null,
                figures ? totaux.tva() : null,
                figures ? totaux.ttc() : null,
                detailedLines,
                c.getObjectClause(), c.getDeliveryClause(), c.getWarrantyClause(),
                c.getOwnershipClause(), c.getDisputeClause(), c.getSpecialTerms());
    }

    private record Totaux(BigDecimal ht, BigDecimal tva, BigDecimal ttc) {}

    private static Totaux totaux(List<Vehicle> lines, BigDecimal tvaRate) {
        BigDecimal ht = lines.stream()
                .map(Vehicle::getUnitPriceHt)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal tva = ht.multiply(tvaRate).setScale(2, RoundingMode.HALF_UP);
        // HT + TVA = TTC exactement : le TTC est dérivé de la TVA arrondie, pas d'un second calcul.
        return new Totaux(ht, tva, ht.add(tva));
    }
}
