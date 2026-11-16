package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.InterventionPrime;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Allers-retours des primes d'intervention.
 *
 * Rien ici n'est saisissable comme montant : un humain n'écrit qu'une
 * justification. La durée, les heures facturables et le total viennent des
 * horodatages posés par les boutons d'étape, et le taux vient de la fiche du
 * technicien. C'est précisément ce que le module est venu chercher — une prime
 * qui ne peut pas être tapée au clavier.
 *
 * {@link PrimeDto#rule()} énonce la règle de calcul sur l'écran : une règle
 * cachée se discute, une règle écrite à l'avance se contrôle.
 */
public final class PrimeDtos {

    private PrimeDtos() {}

    /**
     * La prime d'une sortie : soit le calcul en cours (brouillon, {@code validated=false}),
     * soit la ligne validée telle qu'elle a été payée. Deux valeurs de durée sont
     * données parce qu'un plafond s'applique : {@code realMinutes} est ce qui s'est
     * réellement passé, {@code paidMinutes} ce qui est retenu pour payer.
     */
    public record PrimeDto(String interventionId, String interventionNumber,
                           String status, String technicianId, String technicianName,
                           Long realMinutes, Long paidMinutes, BigDecimal billableHours,
                           BigDecimal hourlyRate, BigDecimal dispatchAllowance, BigDecimal amount,
                           List<String> anomalies, boolean validated,
                           LocalDateTime validatedAt, String validatedByEmail, String justification,
                           String rule) {

        public static PrimeDto saved(InterventionPrime p, String number, String status,
                                     Long realMinutes, String technicianId, String technicianName,
                                     String rule) {
            return new PrimeDto(p.getInterventionId(), number, status, technicianId, technicianName,
                    realMinutes, p.getPaidMinutes(), p.getBillableHours(), p.getHourlyRate(),
                    p.getDispatchAllowance(), p.getAmount(), split(p.getAnomalies()), true,
                    p.getValidatedAt(), p.getValidatedByEmail(), p.getJustification(), rule);
        }

        public static List<String> split(String anomalies) {
            if (anomalies == null || anomalies.isBlank()) {
                return List.of();
            }
            return List.of(anomalies.split(" ; "));
        }
    }

    /** Validation : la justification n'est facultative que si le serveur n'a rien signalé. */
    public record PrimeValidationRequest(@Size(max = 500) String justification) {}

    /** Une ligne du tableau de la période. */
    public record PrimeLine(String primeId, String interventionId, String interventionNumber,
                            String technicianId, String technicianName,
                            BigDecimal billableHours, BigDecimal amount,
                            LocalDateTime validatedAt, String validatedByEmail,
                            List<String> anomalies, String justification) {}

    /** Ce qui se paie à un technicien sur la période, regroupé. */
    public record PrimeTotals(String technicianId, String technicianName,
                              long sorties, BigDecimal hours, BigDecimal amount) {}

    /** Tableau de la période : les lignes, les totaux par technicien et le total général. */
    public record PrimePeriodResponse(String period, LocalDateTime from, LocalDateTime to,
                                      List<PrimeLine> lines, List<PrimeTotals> byTechnician,
                                      BigDecimal grandTotal, String rule) {}
}
