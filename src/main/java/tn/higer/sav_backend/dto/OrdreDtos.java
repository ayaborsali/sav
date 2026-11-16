package tn.higer.sav_backend.dto;

import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartResponse;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceResponse;
import tn.higer.sav_backend.dto.PhotoDtos.PhotoResponse;

import java.util.List;

/**
 * Ce que la fiche de l'ordre de travail montre, et ce qui manque pour le clôturer.
 *
 * Deux records separés du reste parce qu'ils ne portent pas des cases du papier, mais
 * ce que le papier ne sait pas tout seul : de quel devis il est né, quelle sortie l'a
 * vu naître, quel palier il acquitte et combien de paliers le forfait a déjà faits.
 * Les maquettes les affichent en tête de fiche ; les recalculer à l'écran voudrait
 * dire que l'interface devine une origine — et une origine devinée n'est pas une
 * preuve.
 */
public final class OrdreDtos {

    private OrdreDtos() {}

    /**
     * Les origines nommées. {@code numeroSource} est le papier dont celui-ci est né
     * ({@code source_item_id}, V35) ; {@code numeroDevis} remonte la chaîne d'un
     * cran de plus, pour qu'un bon de livraison puisse dire « Devis DEV-… » sans que
     * l'écran ait à chercher. {@code numeroMission} est la demande (MT-) que le lien
     * « Ouvrir la mission » ouvre ; {@code numeroSortie} est la sortie (DP-) dont
     * l'ordre reprend les photos.
     */
    public record OrigineDto(String numeroSource, String typeSource, String numeroDevis,
                             String missionId, String numeroMission,
                             String sortieId, String numeroSortie) {}

    /**
     * Le forfait engagé et le palier que ce papier acquitte. {@code etatDuPalier} est
     * lu AVANT la clôture : « À faire » se change en « Fait » par le clic, et
     * {@code tenuPar} nomme le papier qui l'a déjà coché — c'est le nom que lit
     * l'atelier quand la clôture se refuse, pas un identifiant technique.
     */
    public record ForfaitDto(String labelForfait, String labelPalier, String etatDuPalier,
                             Integer paliersFaits, Integer paliersTotal, String tenuPar) {}

    /**
     * Les trois portes de la preuve, telles que l'atelier les lit. Une seule suffit :
     * la photo « après » déjà versée sur la sortie ({@code photoApresDeLaSortie}), la
     * fiche d'atelier signée jointe à ce papier ({@code ficheSigneeJointe}), ou la
     * raison écrite ({@code raisonEcrite}, traitée au journal). {@code sortieId} sert
     * au lien de l'écran : la preuve se relit là où elle a été versée, pas recopiée.
     */
    public record JustificatifDto(String sortieId, String numeroSortie, boolean photoApresDeLaSortie,
                                  boolean ficheSigneeJointe, boolean raisonEcrite, String message) {}

    /**
     * Ce qui manque avant de clôturer, écrit par le serveur dans l'ordre où l'atelier
     * le corrige : les opérations pas encore faites, puis les heures, puis la preuve.
     * {@code pret} veut dire « le clic passera sans rien taper de plus » — un bouton
     * qui refuse n'a pas de secret.
     */
    public record EtatClotureDto(boolean pret, List<String> operationsNonCochees,
                                 List<String> heuresManquantes, JustificatifDto justificatif,
                                 List<String> ceQuiManque, String message) {}

    /**
     * La fiche d'un papier de l'atelier : le papier lui-même, ses origines nommées, le
     * forfait qu'il touche, les photos de sa sortie d'origine, les pièces posées
     * dessus, l'état de sa clôture, et le bloc du bon de livraison. Une seule requête,
     * pour que l'écran n'additionne pas six appels pour afficher une ligne.
     *
     * {@code cloture} n'est rempli que pour un ordre de travail, {@code bon} que pour un
     * bon de livraison : chaque papier ne porte que ce qu'il peut prouver.
     */
    public record FicheResponse(MaintenanceResponse papier, OrigineDto origine, ForfaitDto forfait,
                                List<PhotoResponse> photosSortie, List<ItemPartResponse> piecesPosees,
                                EtatClotureDto cloture, BonLivraisonDtos.BonDto bon) {}
}
