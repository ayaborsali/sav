package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.InterventionPartRequest;

import java.time.LocalDateTime;

/**
 * La pièce demandée sur une sortie.
 *
 * Quatre cases à l'écran, et c'est tout ce que l'homme dehors a à écrire : le code
 * constructeur, ce que c'est, combien, et pourquoi. Le bus, le dossier, la mission et
 * l'état ne lui sont pas demandés — le serveur les reprend de la sortie. Une case de
 * plus serait une case où l'on peut se tromper deux fois.
 */
public final class InterventionPartDtos {

    private InterventionPartDtos() {}

    /**
     * La demande du technicien, au moment du diagnostic.
     *
     * La référence peut rester vide : un code vient de Higer ou ne se lit pas sur la
     * pièce morte, et une case remplie d'un code inventé coûte un mauvais ordre de
     * commande. La désignation et la raison, elles, sont exigées — sans elles le
     * guichet commande à l'aveugle et personne ne peut dire six mois après pourquoi
     * cette pièce est partie.
     */
    public record PartDemandRequest(@Size(max = 120) String reference,
                                    @NotBlank @Size(max = 300) String designation,
                                    @NotNull Integer quantity,
                                    @NotBlank @Size(max = 1000) String reason) {}

    /**
     * Ce que le magasin écrit en réceptionnant : la quantité arrivée <b>à ce
     * passage</b>, pas le cumul. Le cumul se relit de la ligne ; le redemander à
     * chaque livraison, c'est deux façons de se tromper sur le même chiffre.
     */
    public record PartReceptionRequest(@NotNull Integer quantity) {}

    /**
     * La ligne telle que l'écran la lit. Les trois liens (sortie, mission, dossier)
     * sortent avec leur numéro, parce qu'un identifiant seul ne se vérifie pas à
     * l'œil : le chef doit pouvoir voir « c'est bien DP-2026-019, MT-2026-014 ».
     *
     * Les deux quantités et les deux couples date/nom sortent aussi : « Partielle »
     * ne se comprend que par l'écart entre commandé et reçu, et une date sans nom ne
     * se discute pas en litige.
     *
     * La fin du record porte la suite de l'histoire, et elle sort parce qu'elle est
     * écrite par le serveur, pas par un poste : la mission « changer la pièce » que la
     * livraison a ouverte ({@code fittingMission*}) et la seconde sortie qui l'a montée
     * ({@code fitted*}). Un écran qui ne montrerait pas la mission ouverte laisserait
     * croire au guichet que rien ne reste à faire.
     */
    public record PartDemandResponse(String id,
                                     String interventionId, String interventionNumber,
                                     String missionId, String missionNumber,
                                     String savCaseId, String savCaseNumber,
                                     String vehicleId, String vehicleLabel,
                                     String reference, String designation, String reason,
                                     Integer quantityOrdered, Integer quantityReceived,
                                     LocalDateTime orderedAt, String orderedBy,
                                     LocalDateTime receivedAt, String receivedBy,
                                     String state, String stateLabel,
                                     String demandedBy, LocalDateTime demandedAt,
                                     String fittingMissionId, String fittingMissionNumber,
                                     String fittedInterventionId, String fittedInterventionNumber,
                                     LocalDateTime fittedAt, String fittedBy) {}

    public static PartDemandResponse ligne(InterventionPartRequest p, String interventionNumber,
                                           String missionNumber, String savCaseNumber,
                                           String vehicleLabel, String fittingMissionNumber,
                                           String fittedInterventionNumber) {
        return new PartDemandResponse(p.getId(),
                p.getInterventionId(), interventionNumber,
                p.getMissionId(), missionNumber,
                p.getSavCaseId(), savCaseNumber,
                p.getVehicleId(), vehicleLabel,
                p.getReference(), p.getDesignation(), p.getReason(),
                p.getQuantityOrdered(), p.getQuantityReceived(),
                p.getOrderedAt(), p.getOrderedBy(),
                p.getReceivedAt(), p.getReceivedBy(),
                p.getState() == null ? null : p.getState().name().toLowerCase(),
                p.getState() == null ? null : p.getState().label(),
                p.getDemandedBy(), p.getCreatedAt(),
                p.getFittingMissionId(), fittingMissionNumber,
                p.getFittedInterventionId(), fittedInterventionNumber,
                p.getFittedAt(), p.getFittedBy());
    }
}
