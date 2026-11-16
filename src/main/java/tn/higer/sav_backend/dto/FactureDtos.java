package tn.higer.sav_backend.dto;

import tn.higer.sav_backend.model.Encaissement;
import tn.higer.sav_backend.model.Facture;
import tn.higer.sav_backend.model.FactureLine;
import tn.higer.sav_backend.model.FactureStatus;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.service.FactureEtats;

import java.time.LocalDate;
import java.util.List;

/**
 * Les factures, dans les deux sens (I01, I02).
 *
 * Le nom du client et du bus est porté dans la réponse même si la base ne stocke que
 * des identifiants : l'écran qui liste les factures doit dire « FA-2026-004 — Sfax
 * Transport — CS 1234 » sans relancer trois appels. La même règle que les forfaits.
 *
 * Les montants (total TTC, encaissé, restant dû, prix des lignes) sont mis à
 * {@code null} à la sortie quand le compte n'a pas droit aux chiffres commerciaux —
 * le masquage est serveur, pas un « hidden » d'écran : l'onglet réseau se lit.
 *
 * ⚠️ Les noms des composantes sont les clés du JSON, donc le contrat de l'écran
 * ({@code core/types.ts} côté Angular). Renommer « etat » en « statut » d'un seul
 * côté ne casse ni le serveur (200 OK) ni la compilation Angular : la liste s'affiche
 * vide chez le guichet. C'est le défaut du 04/11, et un essai épingle ces noms.
 */
public final class FactureDtos {

    private FactureDtos() {}

    /** Émettre une facture depuis un BL signé. L'échéance est facultative (défaut serveur : +30 j). */
    public record FactureEmissionRequest(String blId, LocalDate dueOn) {}

    /** Encaisser un versement. Le montant est borné au restant dû côté serveur ; la date est le jour du clic. */
    public record EncaissementRequest(Double amount, String mode, String note) {}

    /** Une ligne de la facture, recopiée du devis. Prix masqués si le compte n'y a pas droit. */
    public record FactureLineDto(String reference, String label, Double quantity, Double unitPrice,
                                 Double lineTotal, String nature, String suppliedBy) {

        static FactureLineDto from(FactureLine line, boolean pricesVisible) {
            return new FactureLineDto(line.getReference(), line.getLabel(), line.getQuantity(),
                    argent(line.getUnitPrice(), pricesVisible), argent(line.getLineTotal(), pricesVisible),
                    line.getNature() == null ? null : line.getNature().name(),
                    line.getSuppliedBy() == null ? null : line.getSuppliedBy().name());
        }
    }

    /** Un versement rentré. Le montant est masqué comme le reste de l'argent. */
    public record EncaissementDto(String id, Double amount, LocalDate paidOn, String mode,
                                  String recordedBy, String note) {

        static EncaissementDto from(Encaissement e, boolean pricesVisible) {
            return new EncaissementDto(e.getId(), argent(e.getAmount(), pricesVisible), e.getPaidOn(),
                    e.getMode(), e.getRecordedBy(), e.getNote());
        }
    }

    /**
     * Une ligne de la liste des factures : assez pour suivre l'argent sans ouvrir la
     * fiche. L'état et le retard se calculent ici, à la date du jour, pour qu'une
     * créance qui vieillit se colore sans qu'on ait à y penser.
     */
    public record FactureSummary(String id, String number, String blNumber, String clientId, String clientName,
                                 String vehicleId, String vehicleRegistration, LocalDate issuedOn, LocalDate dueOn,
                                 Double totalTTC, Double montantEncaisse, Double restantDu,
                                 String etat, String etatLabel, boolean enRetard) {

        public static FactureSummary of(Facture f, String blNumber, String clientName, String vehicleRegistration,
                                        double encaisse, boolean pricesVisible) {
            LocalDate today = LocalDate.now();
            FactureStatus etat = FactureEtats.etat(encaisse, f.getTotalTTC(), f.getDueOn(), today);
            return new FactureSummary(f.getId(), f.getNumber(), blNumber, f.getClientId(), clientName,
                    f.getVehicleId(), vehicleRegistration, f.getIssuedOn(), f.getDueOn(),
                    argent(f.getTotalTTC(), pricesVisible), argent(encaisse, pricesVisible),
                    argent(FactureEtats.restantDu(encaisse, f.getTotalTTC()), pricesVisible),
                    etat.name().toLowerCase(), etat.label(),
                    FactureEtats.enRetard(etat, f.getDueOn(), today));
        }
    }

    /** La fiche complète : les lignes, les versements, et ce qui reste à encaisser. */
    public record FactureResponse(String id, String number, String blId, String blNumber,
                                  String devisId, String devisNumber, String clientId, String clientName,
                                  String vehicleId, String vehicleRegistration, LocalDate issuedOn, LocalDate dueOn,
                                  Double totalHT, Double totalTVA, Double totalTTC, Double montantEncaisse,
                                  Double restantDu, String etat, String etatLabel, boolean enRetard,
                                  String createdBy, String notes, List<FactureLineDto> lines,
                                  List<EncaissementDto> encaissements) {

        public static FactureResponse of(Facture f, String blNumber, String devisNumber, String clientName,
                                         String vehicleRegistration, List<Encaissement> encaissements,
                                         boolean pricesVisible) {
            LocalDate today = LocalDate.now();
            double encaisse = FactureEtats.sommeEncaissee(encaissements);
            FactureStatus etat = FactureEtats.etat(encaisse, f.getTotalTTC(), f.getDueOn(), today);
            return new FactureResponse(f.getId(), f.getNumber(), f.getBlId(), blNumber,
                    f.getDevisId(), devisNumber, f.getClientId(), clientName, f.getVehicleId(),
                    vehicleRegistration, f.getIssuedOn(), f.getDueOn(),
                    argent(f.getTotalHT(), pricesVisible), argent(f.getTotalTVA(), pricesVisible),
                    argent(f.getTotalTTC(), pricesVisible), argent(encaisse, pricesVisible),
                    argent(FactureEtats.restantDu(encaisse, f.getTotalTTC()), pricesVisible),
                    etat.name().toLowerCase(), etat.label(),
                    FactureEtats.enRetard(etat, f.getDueOn(), today),
                    f.getCreatedBy(), f.getNotes(),
                    f.getLines().stream().map(l -> FactureLineDto.from(l, pricesVisible)).toList(),
                    encaissements.stream().map(e -> EncaissementDto.from(e, pricesVisible)).toList());
        }
    }

    /**
     * Un bon de livraison signé jamais facturé (I02) : la relance. Le montant à
     * facturer est celui du devis accepté en amont ; {@code null} quand le BL n'a pas
     * de devis — le geste « Facturer » le refusera alors en nommant ce qui manque.
     */
    public record AFacturerDto(String blId, String blNumber, String clientId, String clientName,
                               String vehicleId, String vehicleRegistration, LocalDate signedOn,
                               long joursDepuisSignature, String devisNumber, Double montantAFacturer) {

        public static AFacturerDto of(MaintenanceItem bl, String clientName, String vehicleRegistration,
                                      String devisNumber, Double montantTTC, boolean pricesVisible) {
            long jours = bl.getSignedOn() == null ? 0
                    : java.time.temporal.ChronoUnit.DAYS.between(bl.getSignedOn(), LocalDate.now());
            return new AFacturerDto(bl.getId(), bl.getNumber(), bl.getClientId(), clientName,
                    bl.getVehicleId(), vehicleRegistration, bl.getSignedOn(), jours, devisNumber,
                    argent(montantTTC, pricesVisible));
        }
    }

    private static Double argent(Double valeur, boolean visible) {
        return visible ? valeur : null;
    }
}