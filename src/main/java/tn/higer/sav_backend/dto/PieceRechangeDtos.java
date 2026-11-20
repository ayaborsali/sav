package tn.higer.sav_backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.PieceFamily;
import tn.higer.sav_backend.model.PieceRechange;
import tn.higer.sav_backend.model.PieceType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Le catalogue des pièces de rechange, dans les deux sens.
 *
 * Les montants circulent en {@code BigDecimal} et non en nombre à virgule simple :
 * un prix de pièce se lit au millime, et un {@code double} qui a voyagé dans du
 * JSON rend parfois {@code 1234.5678999999999} sur la ligne d'un devis. C'est un
 * écart volontaire à ce que fait le module des forfaits, et il suit la règle du
 * portail : un montant est un {@code BigDecimal}, partout.
 *
 * Une demande ne porte — jamais — {@code prixRevient} ni {@code prixVente} : ces
 * deux cases sont le résultat du calcul serveur, pas une saisie. Ce que l'écran
 * affiche à côté du formulaire est un aperçu, et l'enregistrement le recalcule. Le
 * prix d'une pièce ne se négocie pas depuis le navigateur.
 *
 * Les deux coefficients, eux, se saisissent : celui du revient porte le transport,
 * le dédouanement et la devise, celui de la vente porte la marge. Le second est
 * obligatoire depuis V47 : sans lui, la pièce n'a pas de prix de vente, elle n'en
 * a pas un à zéro.
 *
 * Comme pour les contrats et les forfaits, un compte sans droit aux chiffres
 * commerciaux reçoit une réponse dont les montants sont <b>absents</b>
 * ({@code null}), et non pas un écran qui les grise : la requête se lit dans
 * l'onglet réseau.
 */
public final class PieceRechangeDtos {

    private PieceRechangeDtos() {}

    /** Création ou modification complète d'une pièce : {@code PUT} remplace la ligne telle quelle. */
    public record PieceRequest(
            @NotBlank(message = "La référence de la pièce est obligatoire : c'est elle qu'on redemande au constructeur.")
            @Size(max = 160, message = "La référence d'une pièce ne dépasse pas 160 caractères.")
            String reference,

            /**
             * Le numéro du fournisseur, quand il diffère de la référence Higer. Il ne
             * remplace pas {@link #reference} : c'est celle-là que le service rend
             * unique et qu'on redemande au constructeur.
             */
            @Size(max = 160, message = "La référence du fournisseur ne dépasse pas 160 caractères.")
            String referenceFournisseur,

            @NotBlank(message = "La désignation en français est obligatoire : c'est le mot que le lecteur du devis comprend.")
            @Size(max = 300, message = "La désignation française ne dépasse pas 300 caractères.")
            String designationFr,

            @Size(max = 300, message = "La désignation anglaise ne dépasse pas 300 caractères.")
            String designationEn,

            /** Le « type PR » de l'atelier. Une seule famille, choisie parmi les sept de {@link PieceFamily}. */
            @NotBlank(message = "La famille de la pièce est obligatoire : c'est elle qui permet de la retrouver dans le catalogue.")
            String famille,

            /**
             * Le « type » de l'atelier : Mecanique ou Electrique, les deux mots de
             * {@link PieceType}. Case vide = « à classer » — la pièce ne devient pas
             * Mécanique par défaut, et l'écran écrit « — ».
             */
            @Size(max = 16, message = "Le type de la pièce se lit parmi les deux mots du catalogue.")
            String typePiece,

            @NotNull(message = "Le prix Ex Works est obligatoire : sans lui, aucun prix de revient ne se calcule.")
            @DecimalMin(value = "0.0001", message = "Le prix Ex Works doit être positif.")
            @Digits(integer = 9, fraction = 3, message = "Le prix Ex Works se donne au millime près, pas davantage.")
            BigDecimal prixExWorks,

            @NotNull(message = "Le coefficient de revient est obligatoire : c'est lui qui porte le transport, le dédouanement et la devise.")
            @DecimalMin(value = "0.0001", message = "Le coefficient de revient doit être positif.")
            @Digits(integer = 6, fraction = 4, message = "Le coefficient de revient se donne au 1/10 000ᵉ près, pas davantage.")
            BigDecimal coefficientRevient,

            /**
             * Saisie obligatoire : sans taux, pas de prix de vente. Le prix lui-même
             * n'est jamais envoyé — le serveur le recalcule à chaque enregistrement.
             */
            @NotNull(message = "Le coefficient de vente est obligatoire : c'est lui qui fixe le prix de vente de la pièce.")
            @DecimalMin(value = "0.0001", message = "Le coefficient de vente doit être positif.")
            @Digits(integer = 6, fraction = 4, message = "Le coefficient de vente se donne au 1/10 000ᵉ près, pas davantage.")
            BigDecimal coefficientVente,

            @Size(max = 160, message = "Le nom du fournisseur ne dépasse pas 160 caractères.")
            String fournisseur,

            /**
             * Le prix annoncé par un atelier concurrent, en dinars. Facultatif : un
             * prix calculé ne le remplace pas, et il ne remplace aucun des deux prix
             * calculés. Il doit être positif quand il est écrit — zéro se lirait
             * comme le prix le plus bas du marché.
             */
            @DecimalMin(value = "0.0001", message = "Le prix compétiteur doit être positif.")
            @Digits(integer = 9, fraction = 3, message = "Le prix compétiteur se donne au millime près, pas davantage.")
            BigDecimal prixCompetiteur,

            @Pattern(regexp = PieceRechange.REGEX_DEVISE,
                    message = "La devise doit être EUR, USD, TND ou CNY (EUR si la case est vide).")
            String devise
    ) {

        /**
         * Une demande sans type : c'est le chemin du classeur, dont aucune colonne ne
         * porte les deux mots. Le type reste « à classer » et l'atelier le met à
         * l'écran — l'import n'en invente pas à partir de la famille.
         */
        public PieceRequest(String reference, String referenceFournisseur, String designationFr,
                            String designationEn, String famille, BigDecimal prixExWorks,
                            BigDecimal coefficientRevient, BigDecimal coefficientVente,
                            String fournisseur, BigDecimal prixCompetiteur, String devise) {
            this(reference, referenceFournisseur, designationFr, designationEn, famille, null, prixExWorks,
                    coefficientRevient, coefficientVente, fournisseur, prixCompetiteur, devise);
        }
    }

    /** Une ligne du catalogue telle que l'écran la lit. */
    public record PieceResponse(String id, String reference, String referenceFournisseur, String designationFr,
                               String designationEn, String famille, String typePiece, BigDecimal prixExWorks,
                               String devise, BigDecimal coefficientRevient, BigDecimal coefficientVente,
                               BigDecimal prixRevient, BigDecimal prixVente, String fournisseur,
                               BigDecimal prixCompetiteur, boolean active,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {

        public static PieceResponse from(PieceRechange piece, boolean prixVisibles) {
            return new PieceResponse(
                    piece.getId(), piece.getReference(), piece.getReferenceFournisseur(),
                    piece.getDesignationFr(), piece.getDesignationEn(),
                    // Ni la famille ni le type ne sont des chiffres commerciaux : ils se
                    // montrent à tout compte connecté.
                    piece.getFamille() == null ? null : piece.getFamille().name(),
                    piece.getTypePiece() == null ? null : piece.getTypePiece().name(),
                    prixVisibles ? piece.getPrixExWorks() : null, piece.getDevise(),
                    prixVisibles ? piece.getCoefficientRevient() : null,
                    prixVisibles ? piece.getCoefficientVente() : null,
                    prixVisibles ? piece.getPrixRevient() : null,
                    prixVisibles ? piece.getPrixVente() : null,
                    piece.getFournisseur(),
                    // Le prix du compétiteur est un chiffre commercial : il sort de la
                    // réponse avec les autres, et pas même un « plus cher / moins cher ».
                    prixVisibles ? piece.getPrixCompetiteur() : null,
                    piece.isActive(), piece.getCreatedAt(), piece.getUpdatedAt());
        }
    }

    /** Où le problème a été vu : la feuille, la ligne telle qu'Excel la numérote, la colonne, la raison. */
    public record Issue(String sheet, Integer row, String column, String message) {}

    /**
     * Une famille du catalogue, telle que l'écran la met dans son select :
     * {@code code} est ce que la base garde et ce que la demande doit renvoyer
     * (le nom de l'enum, sans accent), {@code label} est le mot que l'atelier lit.
     *
     * La liste sort du serveur et n'est pas recopiée dans l'écran : c'est la base
     * qui garde les sept mots fermés, et un huitième rangé par une migration doit
     * se proposer de lui-même plutôt qu'attendre qu'on pense à l'écran.
     *
     * Le même couple sert aux deux mots du « type » de la pièce : une liste fermée,
     * c'est une liste fermée, qu'elle en contienne sept ou deux.
     */
    public record PieceFamilyOption(String code, String label) {}

    /**
     * Une ligne comprise dans le classeur. Elle ne porte aucun prix calculé : le
     * serveur recalculera le prix de revient et le prix de vente à l'enregistrement,
     * exactement comme pour une saisie à l'écran, pour qu'un classeur mal monté ne
     * puisse pas imposer un total. Les deux coefficients, eux, viennent du fichier
     * (ou de la valeur saisie avant l'import) : ce sont des taux, pas des résultats.
     *
     * {@code dejaAuCatalogue} est un renseignement pour l'aperçu : la même
     * référence s'y trouve déjà, la confirmation mettra à jour au lieu d'ajouter.
     */
    public record PieceImportLine(
            Integer row,
            @NotBlank(message = "La référence de la pièce est obligatoire : c'est elle qu'on redemande au constructeur.")
            @Size(max = 160, message = "La référence d'une pièce ne dépasse pas 160 caractères.")
            String reference,
            @Size(max = 160, message = "La référence du fournisseur ne dépasse pas 160 caractères.")
            String referenceFournisseur,
            @NotBlank(message = "La désignation en français est obligatoire : c'est le mot que le lecteur du devis comprend.")
            @Size(max = 300, message = "La désignation française ne dépasse pas 300 caractères.")
            String designationFr,
            @Size(max = 300, message = "La désignation anglaise ne dépasse pas 300 caractères.")
            String designationEn,
            /** Une des sept familles du catalogue, déjà lue et retenue par l'import. */
            @NotBlank(message = "La famille de la pièce est obligatoire : c'est elle qui permet de la retrouver dans le catalogue.")
            String famille,
            @NotNull(message = "Le prix Ex Works est obligatoire : sans lui, aucun prix de revient ne se calcule.")
            @DecimalMin(value = "0.0001", message = "Le prix Ex Works doit être positif.")
            @Digits(integer = 9, fraction = 3, message = "Le prix Ex Works se donne au millime près, pas davantage.")
            BigDecimal prixExWorks,
            @NotNull(message = "Le coefficient de revient est obligatoire : saisis-le avant l'import ou remplis la colonne qui manque.")
            @DecimalMin(value = "0.0001", message = "Le coefficient de revient doit être positif.")
            @Digits(integer = 6, fraction = 4, message = "Le coefficient de revient se donne au 1/10 000ᵉ près, pas davantage.")
            BigDecimal coefficientRevient,
            @NotNull(message = "Le coefficient de vente est obligatoire : saisis-le avant l'import ou remplis la colonne qui manque.")
            @DecimalMin(value = "0.0001", message = "Le coefficient de vente doit être positif.")
            @Digits(integer = 6, fraction = 4, message = "Le coefficient de vente se donne au 1/10 000ᵉ près, pas davantage.")
            BigDecimal coefficientVente,
            @Size(max = 160, message = "Le nom du fournisseur ne dépasse pas 160 caractères.")
            String fournisseur,
            @DecimalMin(value = "0.0001", message = "Le prix compétiteur doit être positif.")
            @Digits(integer = 9, fraction = 3, message = "Le prix compétiteur se donne au millime près, pas davantage.")
            BigDecimal prixCompetiteur,
            @Pattern(regexp = PieceRechange.REGEX_DEVISE,
                    message = "La devise doit être EUR, USD, TND ou CNY (EUR si la case est vide).")
            String devise,
            boolean dejaAuCatalogue
    ) {

        /** La ligne telle qu'elle deviendra une demande d'enregistrement : même contenu, sans le renseignement d'aperçu. */
        public PieceRequest toRequest() {
            return new PieceRequest(reference, referenceFournisseur, designationFr, designationEn, famille,
                    prixExWorks, coefficientRevient, coefficientVente, fournisseur, prixCompetiteur, devise);
        }

        public static PieceImportLine of(int row, PieceRequest request, boolean dejaAuCatalogue) {
            return new PieceImportLine(row, request.reference(), request.referenceFournisseur(),
                    request.designationFr(), request.designationEn(), request.famille(), request.prixExWorks(),
                    request.coefficientRevient(), request.coefficientVente(), request.fournisseur(),
                    request.prixCompetiteur(), request.devise(), dejaAuCatalogue);
        }
    }

    /**
     * L'aperçu d'un import : ce qui serait enregistré, et ce qui l'empêche.
     *
     * Rien n'est écrit en base à ce stade. {@code canImporter()} dit seulement si
     * la confirmation passera — un classeur qui contient une seule ligne refusée
     * n'est pas enregistré ligne par ligne, l'atelier corrige son fichier ou
     * accepte ce qu'il reste.
     */
    public record PieceImportPreview(List<PieceImportLine> lignes, List<Issue> erreurs, List<Issue> avertissements) {

        public boolean canImporter() {
            return erreurs.isEmpty();
        }

        public int lignesValides() {
            return lignes.size();
        }

        /** Combien de ces lignes vont remplacer une pièce déjà au catalogue — à dire avant, pas après. */
        public int misesAJourPrevues() {
            return (int) lignes.stream().filter(PieceImportLine::dejaAuCatalogue).count();
        }
    }

    /** Ce que la confirmation envoie au serveur, après un aperçu relu à l'écran. */
    public record PieceImportCommit(@Valid List<PieceImportLine> lignes) {}

    /** Le résumé rendu après un import confirmé : l'atelier doit savoir ce qui a bougé. */
    public record PieceImportSummary(int ajoutees, int misesAJour, int refusees, List<Issue> details) {}
}
