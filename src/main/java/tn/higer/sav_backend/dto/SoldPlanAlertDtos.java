package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Allers-retours des rappels de paliers (module Forfaits).
 *
 * Une alerte ne se saisit pas : c'est le serveur qui la déclenche en comparant le
 * compteur connu du bus aux bornes du palier. Le seul champ qu'un humain écrit est
 * la raison qui le lève — et elle est exigée, sinon le signal disparaîtrait sans
 * que personne n'ait répondu.
 *
 * Le bus, le client et le palier sont portés dans la réponse même si la base garde
 * des identifiants : l'atelier doit pouvoir lire « CS 1234 — Sfax Transport —
 * visite des 40 000 km » sans rouvrir trois fiches. Aucun montant n'y figure : un
 * rappel dit qu'une visite est à faire, pas ce qu'elle coûte — la question de
 * l'argent reste sur la fiche du forfait, où elle est déjà masquée au rôle
 * consultation.
 */
public final class SoldPlanAlertDtos {

    private SoldPlanAlertDtos() {}

    /**
     * Un rappel. {@code compteurConnu} et {@code sourceCompteur} disent avec quel
     * chiffre la comparaison a été faite et d'où il vient : sans cela, un atelier en
     * droit de douter d'un rappel n'aurait que la réponse, pas la preuve.
     *
     * {@code sortieId} et {@code sortieNumber} signalent la prise en charge : une
     * sortie d'entretien est déjà partie pour CE palier. La ligne reste ouverte — une
     * équipe en route n'est pas une visite faite — mais elle ne se lit plus comme un
     * oubli.
     *
     * {@code dossierVisiteId} et {@code dossierVisiteNumber} disent l'autre moitié de
     * la même question : un dossier de visite est déjà ouvert sur CE palier. Sans
     * cela, « visite périodique » voudrait dire deux choses qui ne se connaissent
     * pas — le rappel sorti du compteur d'un côté, le papier tapé à l'atelier de
     * l'autre — et la visite des 40 000 km pourrait être faite deux fois, ou aucune.
     */
    public record PlanAlertDto(String id, String soldPlanId, String planName,
                               String clientId, String clientName, String vehicleId,
                               String vehicleVin, String vehicleRegistration,
                               String stepId, String palierLabel, Long palierKm, Integer palierDays,
                               LocalDate dateDebutForfait, Long compteurConnu, String sourceCompteur,
                               String kind, String kindLabel, String message, LocalDateTime detectedAt,
                               boolean waived, String waiverReason, String waivedByEmail,
                               LocalDateTime waivedAt, String sortieId, String sortieNumber,
                               String dossierVisiteId, String dossierVisiteNumber) {}

    /** Les rappels encore ouverts, avec la règle qui les a fait naître. */
    public record PlanAlertListResponse(List<PlanAlertDto> alerts, long openCount, String rules,
                                        LocalDateTime referenceTime) {}

    /** Lever un rappel : la raison est exigée, sinon il reste ouvert. */
    public record WaiveRequest(@Size(max = 500) String reason) {}

    /**
     * Résultat d'une vérification demandée depuis l'écran : ce que la passe a
     * ouvert, et la liste telle qu'elle est maintenant, pour que le poste n'ait pas
     * à relancer une deuxième requête sur des données déjà toutes faites.
     */
    public record ScanResponse(int opened, PlanAlertListResponse alerts) {}
}
