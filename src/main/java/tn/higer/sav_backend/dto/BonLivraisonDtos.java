package tn.higer.sav_backend.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Ce que la fiche du bon de livraison montre, et ce que le papier ne sait pas tout seul.
 *
 * Le BL est le papier que le CLIENT tient en main à la restitution du bus. Il ne
 * travaille pas et il ne chiffre pas : il rend. Trois choses ne se lisent donc pas sur
 * ses seules colonnes — le travail tel que l'ordre l'a fait et tel qu'il a été vu sur le
 * bus, l'homme qui l'a fait dehors, et le compteur que la fiche du bus connaît déjà au
 * moment où l'atelier en tape un autre.
 *
 * Tout vient du serveur, y compris ce qui manque pour signer : l'écran colore, il ne
 * décide pas. Et aucun montant : {@code MaintenanceTotals} ne calcule plus pour ce
 * papier (V39), la maquette est formelle — ni prix, ni TVA, ni TTC sur un bon de
 * livraison.
 */
public final class BonLivraisonDtos {

    private BonLivraisonDtos() {}

    /**
     * Le travail rendu, tel que l'ordre l'a écrit. {@code numeroOrdre} et
     * {@code dateDeCloture} nomment le papier dont celui-ci sort ; {@code anomalies} est
     * ce que l'atelier a VU sur le bus pendant le travail. La case n'existe que sur
     * l'ordre (V38), et le client doit la lire à la restitution : sinon elle meurt dans
     * un papier qu'il n'a jamais eu en main.
     */
    public record TravailDto(String numeroOrdre, LocalDate dateDeCloture, String anomalies) {}

    /** Une pièce ou un fluide fourni par Saphir, repris des lignes du travail. */
    public record PieceDto(String reference, String designation, Double quantite) {}

    /**
     * L'intervenant, tel que la sortie le dit : l'homme, son départ et sa fin. Le BL ne
     * recopie pas l'heure à l'atelier — une fin de travail tapée à la main est une heure
     * qu'on rapproche quand la prime se discute.
     */
    public record IntervenantDto(String sortieId, String numeroSortie, String technicien,
                                 LocalDateTime depart, LocalDateTime fin) {}

    /**
     * Le compteur du bus, des deux côtés de la case. {@code valeurFiche} est ce que la
     * fiche sait déjà, avec ce qui l'a écrite ; {@code valeurRelevee} est le plus haut
     * relevé lu sur une sortie de ce bus, avec la sortie, l'homme et le jour. L'atelier
     * saisit la restitution entre les deux, et le refus de la porte unique se lit ici
     * plutôt qu'après un clic perdu.
     */
    public record CompteurDto(Long valeurFiche, String origineFiche, String parFiche,
                              Long valeurRelevee, String sortieRelevee, String parReleve,
                              LocalDate jourReleve) {}

    /**
     * Ce qui manque pour « Marquer signé », dans l'ordre où l'atelier le corrige : le nom
     * du signataire, puis le scan joint au papier. {@code pret} veut dire que le clic
     * passera — un bouton qui refuse n'a pas de secret.
     */
    public record EtatSignatureDto(boolean pret, List<String> ceQuiManque, String message) {}

    /**
     * Le bloc du bon de livraison, posé sur la fiche du papier. {@code scanSigneJoint}
     * dit si une preuve est déjà attachée à CE papier — c'est elle que le clic réclame,
     * et elle ne se devine pas : un BL sans fichier signé n'est pas une restitution,
     * c'est une parole.
     */
    public record BonDto(TravailDto travail, List<PieceDto> piecesSaphir, IntervenantDto intervenant,
                         CompteurDto compteur, boolean scanSigneJoint, EtatSignatureDto signature) {}
}
