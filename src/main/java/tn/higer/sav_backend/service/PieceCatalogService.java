package tn.higer.sav_backend.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceFamilyOption;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportLine;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceRequest;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceResponse;
import tn.higer.sav_backend.model.PieceFamily;
import tn.higer.sav_backend.model.PieceRechange;
import tn.higer.sav_backend.model.PieceType;
import tn.higer.sav_backend.repository.PieceRechangeRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Le catalogue des pièces de rechange.
 *
 * Ce service est le seul endroit où une pièce s'écrit, y compris depuis l'import
 * Excel, pour deux raisons qui se paient sinon sur un devis déjà accepté :
 *
 * 1. <b>Les deux prix dérivés ne se saisissent pas, les coefficients si.</b> Le
 *    serveur reçoit le prix Ex Works et les deux coefficients, et il recalcule
 *    {@code prixRevient} puis {@code prixVente} à chaque enregistrement
 *    ({@link PiecePricing}). Un écran qui a fait la multiplication pour l'affichage
 *    n'a rien à dire au moment d'écrire : sinon deux postes du même atelier peuvent
 *    envoyer deux prix différents pour la même pièce.
 *
 * 2. <b>Une référence ne se duplique pas.</b> Deux lignes pour « 2115U-001 », ça
 *    ne se voit pas à la saisie, ça se voit six mois plus tard quand un client
 *    tient deux devis de la même pièce à deux prix. La comparaison se fait sans
 *    casse et sans espaces superflus : la référence d'un classeur et celle frappée
 *    sur la pièce ne diffèrent presque jamais que par là.
 *
 * 3. <b>La famille se choisit, elle ne se déduit pas.</b> Le « type PR » est une
 *    liste fermée de sept mots posée par l'atelier ({@link PieceFamily}), et une
 *    pièce n'en porte jamais qu'une. Un mot hors liste est refusé en nommant les
 *    sept, jamais rangé en « Divers » pour faire passer la ligne : la valeur de ce
 *    champ est justement de retrouver une pièce quand on la cherche par famille, et
 *    une étiquette mise par défaut y mettrait du faux.
 *
 * Une pièce retirée ({@code active = false}) reste en base et reste lisible : les
 * lignes des devis et des ordres d'avant portent sa référence, et l'effacer
 * ferait pointer un papier signé dans le vide. Elle ne se propose plus à une
 * nouvelle saisie, rien de plus.
 */
@Service
public class PieceCatalogService {

    private final PieceRechangeRepository pieces;
    private final AuditService audit;

    public PieceCatalogService(PieceRechangeRepository pieces, AuditService audit) {
        this.pieces = pieces;
        this.audit = audit;
    }

    /**
     * Le catalogue cherché, paginé, trié par référence croissante (posée par la
     * requête du contrôleur), dans une seule famille si l'écran en a choisi une.
     *
     * Le filtre par famille se fait en base, jamais en rappelant tout le catalogue
     * pour trier à l'écran : un atelier qui cherche « tout ce qui est Freinage »
     * prépare une commande de pièces, et une liste tronquée à la première page lui
     * ferait croire qu'il n'y a que ça.
     */
    @Transactional(readOnly = true)
    public Page<PieceResponse> search(String search, String famille, Pageable pageable, boolean prixVisibles) {
        String motif = motifDeRecherche(search);
        PieceFamily borne = filtreFamille(famille);
        Page<PieceRechange> trouve = borne == null
                ? pieces.search(motif, pageable)
                : pieces.searchInFamille(borne, motif, pageable);
        return trouve.map(piece -> PieceResponse.from(piece, prixVisibles));
    }

    @Transactional(readOnly = true)
    public PieceResponse get(String id, boolean prixVisibles) {
        return PieceResponse.from(require(id), prixVisibles);
    }

    /**
     * Ajout d'une pièce. La référence doit être libre ; si elle existe, le refus
     * nomme la ligne à modifier plutôt que de laisser l'atelier croire qu'il a
     * créé une seconde pièce.
     */
    @Transactional
    public PieceResponse create(PieceRequest request, Jwt jwt, boolean prixVisibles) {
        String reference = reference(request.reference());
        if (pieces.existsByReferenceIgnoreCase(reference)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La référence « " + reference + " » est déjà au catalogue : modifie cette ligne au lieu d'en "
                            + "ajouter une seconde. Deux lignes pour la même pièce, c'est deux prix différents chez le client.");
        }
        PieceRechange piece = new PieceRechange();
        piece.setId("prc-" + UUID.randomUUID());
        piece.setCreatedAt(LocalDateTime.now());
        appliquer(piece, request, reference);
        pieces.saveAndFlush(piece);
        audit.record("CREATE", "PIECE_RECHANGE", piece.getId(),
                "Ajout au catalogue de la pièce « " + piece.getReference() + " » (" + piece.getDesignationFr()
                        + ") — " + prixPiece(piece), jwt);
        return PieceResponse.from(piece, prixVisibles);
    }

    /**
     * Modification complète : la ligne devient exactement ce que la demande dit,
     * comme le {@code PUT} des dossiers SAV. Un coefficient non envoyé n'est pas
     * « on garde l'ancienne valeur » : la demande est refusée, sinon un formulaire
     * qui a perdu une case effacerait silencieusement le coefficient de revient
     * d'une pièce et son prix avec lui.
     */
    @Transactional
    public PieceResponse update(String id, PieceRequest request, Jwt jwt, boolean prixVisibles) {
        PieceRechange piece = require(id);
        String reference = reference(request.reference());
        if (pieces.existsByReferenceIgnoreCaseAndIdNot(reference, id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une autre pièce du catalogue porte déjà la référence « " + reference
                            + " ». Corrige sa ligne à elle, ou change la référence de celle-ci.");
        }
        String ancienneReference = piece.getReference();
        appliquer(piece, request, reference);
        pieces.saveAndFlush(piece);
        audit.record("UPDATE", "PIECE_RECHANGE", id,
                "Modification du catalogue : pièce « " + ancienneReference + " » devenue « " + piece.getReference()
                        + " » — " + prixPiece(piece), jwt);
        return PieceResponse.from(piece, prixVisibles);
    }

    /**
     * Retrait du catalogue, sans effacement. Les lignes des devis et des ordres
     * d'avant gardent cette référence, et les papiers déjà imprimés la citent.
     */
    @Transactional
    public void deactivate(String id, Jwt jwt) {
        PieceRechange piece = require(id);
        if (!piece.isActive()) {
            return; // deux clics de retrait ne font pas deux lignes au journal
        }
        piece.setActive(false);
        piece.setUpdatedAt(LocalDateTime.now());
        pieces.saveAndFlush(piece);
        audit.record("DEACTIVATE", "PIECE_RECHANGE", id,
                "Pièce « " + piece.getReference() + " » retirée du catalogue (elle reste lisible pour les papiers d'avant)",
                jwt);
    }

    /**
     * Le chemin de l'import : ajouter si la référence est libre, mettre à jour si
     * elle existe déjà. Un classeur ne fabrique donc jamais deux lignes de la même
     * pièce, et un fichier Higer reçu deux mois d'affilée remplace les prix au lieu
     * d'engorger le catalogue.
     *
     * La mise à jour réactive la pièce si elle avait été retirée : le fichier la
     * redemande, donc elle se procure toujours.
     */
    @Transactional
    public boolean upsertFromImport(PieceImportLine ligne, Jwt jwt) {
        String reference = reference(ligne.reference());
        Optional<PieceRechange> existante = pieces.findByReferenceIgnoreCase(reference);
        PieceRequest demande = ligne.toRequest();
        if (existante.isPresent()) {
            PieceRechange piece = existante.get();
            PieceType dejaClasse = piece.getTypePiece();
            appliquer(piece, demande, reference);
            // Le classeur n'a pas de colonne Type : ce qu'il ne dit pas n'efface pas ce
            // que l'atelier a classé à l'écran.
            piece.setTypePiece(dejaClasse);
            piece.setActive(true);
            pieces.saveAndFlush(piece);
            audit.record("UPDATE", "PIECE_RECHANGE", piece.getId(),
                    "Catalogue importé : pièce « " + piece.getReference() + " » mise à jour — "
                            + prixPiece(piece), jwt);
            return false;
        }
        PieceRechange piece = new PieceRechange();
        piece.setId("prc-" + UUID.randomUUID());
        piece.setCreatedAt(LocalDateTime.now());
        appliquer(piece, demande, reference);
        pieces.saveAndFlush(piece);
        audit.record("CREATE", "PIECE_RECHANGE", piece.getId(),
                "Catalogue importé : pièce « " + piece.getReference() + " » ajoutée — "
                        + prixPiece(piece), jwt);
        return true;
    }

    /** La référence est-elle au catalogue, écrite fût-ce dans une autre casse ? Sert à l'aperçu de l'import. */
    @Transactional(readOnly = true)
    public boolean existe(String reference) {
        String propre = reference == null || reference.isBlank() ? "" : normaliser(reference);
        return !propre.isEmpty() && pieces.existsByReferenceIgnoreCase(propre);
    }

    /**
     * Les sept familles du catalogue, dans l'ordre où l'atelier les a dites. C'est la
     * liste que l'écran met dans son select et dans sa colonne : il ne la recopie pas,
     * parce que le mot que la base garde fermé (la CHECK de V44) et le mot que l'écran
     * propose doivent rester un seul et même mot.
     */
    public List<PieceFamilyOption> familles() {
        return PieceFamily.toutes().stream()
                .map(famille -> new PieceFamilyOption(famille.name(), famille.label()))
                .toList();
    }

    /**
     * Les deux types du catalogue — Mécanique, Électrique — servis par le serveur,
     * jamais recopiés dans l'écran, pour la même raison que les familles : le mot que
     * la base garde fermé et le mot que l'écran proposent doivent rester un seul et
     * même mot.
     */
    public List<PieceFamilyOption> types() {
        return PieceType.toutes().stream()
                .map(type -> new PieceFamilyOption(type.name(), type.label()))
                .toList();
    }

    // ------------------------------------------------------------------ écriture

    /** Une seule écriture des valeurs : l'ajout, la modification et l'import passent tous ici. */
    private void appliquer(PieceRechange piece, PieceRequest request, String reference) {
        piece.setReference(reference);
        // La référence du fournisseur se recolle comme elle a été lue (« AB 12 3 » et
        // « AB 12 3 » se relisent pareil), sans jamais toucher à la référence Higer :
        // c'est celle-là qui décide de l'unicité, pas le numéro de la boîte.
        piece.setReferenceFournisseur(referenceFournisseur(request.referenceFournisseur()));
        piece.setDesignationFr(exigee(request.designationFr()));
        piece.setDesignationEn(texteOptionnel(request.designationEn()));
        piece.setFamille(famille(request.famille()));
        piece.setTypePiece(type(request.typePiece()));
        piece.setDevise(devise(request.devise()));
        piece.setFournisseur(texteOptionnel(request.fournisseur()));
        piece.setPrixCompetiteur(prixCompetiteur(request.prixCompetiteur()));
        piece.setPrixExWorks(request.prixExWorks());
        piece.setCoefficientRevient(request.coefficientRevient());
        piece.setCoefficientVente(request.coefficientVente());

        // Le serveur recalcule, toujours, et dans cet ordre : ce que l'écran a
        // multiplié pour le montrer n'est pas une source de vérité, et un devis se
        // chiffre sur ces colonnes. La vente se monte sur le revient — c'est le
        // chiffre que l'atelier lit sur la ligne au-dessus, arrondi au millime.
        piece.setPrixRevient(PiecePricing.revient(request.prixExWorks(), request.coefficientRevient()));
        piece.setPrixVente(PiecePricing.vente(piece.getPrixRevient(), request.coefficientVente()));
        piece.setUpdatedAt(LocalDateTime.now());
    }

    private PieceRechange require(String id) {
        return pieces.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Pièce introuvable dans le catalogue."));
    }

    private static String exigee(String raw) {
        String value = texteOptionnel(raw);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette case est obligatoire : sans elle, la ligne n'est pas identifiable à l'atelier.");
        }
        return value;
    }

    private static String texteOptionnel(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * La référence s'écrit sans espace superflu (un classeur met souvent « 2115U
     * 001 » là où la pièce porte « 2115U 001 ») mais garde la casse choisie par
     * l'atelier : c'est ce numéro qu'on rappellera au constructeur. La comparaison
     * d'unicité, elle, se fait sans casse, côté requête.
     */
    private static String reference(String raw) {
        String value = texteOptionnel(raw);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La référence de la pièce est obligatoire : sans elle, personne ne peut la redemander.");
        }
        return normaliser(value);
    }

    private static String normaliser(String raw) {
        return raw.trim().replaceAll("\\s+", " ");
    }

    /**
     * La référence du fournisseur, facultative. Elle se recolle comme une référence
     * (les espaces doublés du classeur tombent) mais ne touche jamais à la référence
     * Higer : deux numéros dans la même colonne feraient entrer la même pièce deux
     * fois au catalogue.
     */
    private static String referenceFournisseur(String raw) {
        String value = texteOptionnel(raw);
        return value == null ? null : normaliser(value);
    }

    /**
     * Le prix du compétiteur, facultatif. Écrit, il doit être positif : zéro se
     * lirait « le moins cher du marché » alors que la case n'a pas été remplie, et
     * la base le refuse de toute façon (la CHECK de V59).
     */
    private static BigDecimal prixCompetiteur(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le prix compétiteur doit être positif : un concurrent à zéro dinar n'existe pas, et la case "
                            + "vide dit déjà « pas renseigné ».");
        }
        return value;
    }

    /**
     * La devise d'origine du prix Ex Works. Case vide = EUR, parce que le classeur Higer
     * n'écrit pas toujours la colonne. Une devise hors liste est refusée plutôt que
     * réétiquetée : le prix d'un devis ne change pas de monnaie en silence.
     */
    private static String devise(String raw) {
        String value = texteOptionnel(raw);
        if (value == null) {
            return PieceRechange.DEVISE_PAR_DEFAUT;
        }
        String propre = value.toUpperCase(Locale.ROOT);
        if (!PieceRechange.DEVISES.contains(propre)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La devise « " + value + " » n'est pas connue : choisis EUR, USD, TND ou CNY. "
                            + "Une devise inventée étiquetterait le prix Ex Works de travers.");
        }
        return propre;
    }

    private static String montant(BigDecimal value) {
        return value == null ? "—" : value.toPlainString() + " DT";
    }

    /**
     * La famille d'une pièce qu'on enregistre : un des sept mots de l'atelier, Case
     * vide comprise dans le refus.
     *
     * Le serveur ne range jamais une pièce « pour faire passer la ligne » : une
     * étiquette choisie à la place de l'atelier se retrouve dans toutes les listes
     * filtrées sur cette famille, et rien, à l'écran, ne dit plus tard qui l'a mise.
     */
    private static PieceFamily famille(String raw) {
        String value = texteOptionnel(raw);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La famille de la pièce est obligatoire : choisis-la parmi " + PieceFamily.mots() + ".");
        }
        PieceFamily lue = PieceFamily.lit(value);
        if (lue == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La famille « " + value + " » n'est pas une famille du catalogue : choisis-la parmi "
                            + PieceFamily.mots() + ". Sous un mot inventé, la pièce ne se retrouve plus quand "
                            + "on cherche par famille.");
        }
        return lue;
    }

    /**
     * Le type d'une pièce qu'on enregistre : les deux mots de l'atelier, ou {@code null}
     * si la case est vide. Vide veut dire « à classer », jamais « Mécanique par défaut » :
     * une nature choisie à la place de l'atelier se lit sur la ligne et nulle part elle
     * ne dit qu'elle est supposée.
     *
     * Un mot hors des deux est en revanche refusé, comme la famille — c'est la même
     * liste fermée que la contrainte CHECK de la base.
     */
    private static PieceType type(String raw) {
        String value = texteOptionnel(raw);
        if (value == null) {
            return null;
        }
        PieceType lue = PieceType.lit(value);
        if (lue == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le type « " + value + " » n'est pas un type du catalogue : choisis-le parmi "
                            + PieceType.mots() + ". Une troisième nature est une décision d'atelier, pas une faute de frappe.");
        }
        return lue;
    }

    /**
     * Le filtre par famille d'une lecture. Case vide = pas de filtre, toute la
     * famille demandée = refus qui nomme les sept mots : une liste tronquée par une
     * faute de frappe ressemble à un catalogue vide.
     */
    private static PieceFamily filtreFamille(String raw) {
        String value = texteOptionnel(raw);
        if (value == null) {
            return null;
        }
        PieceFamily lue = PieceFamily.lit(value);
        if (lue == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le filtre « " + value + " » ne désigne aucune famille du catalogue : choisis parmi "
                            + PieceFamily.mots() + ".");
        }
        return lue;
    }

    /**
     * Les deux prix de la pièce et sa famille, tels que le journal les nomme : les
     * deux prix sortent d'un calcul, la famille d'un choix. Une pièce sans
     * coefficient de vente — entrée avant V47 — se lit « à décider » plutôt que
     * « 0 » : le journal d'audit ne doit pas faire croire qu'elle a été revendue
     * gratis.
     */
    private static String prixPiece(PieceRechange piece) {
        return "famille « " + (piece.getFamille() == null ? "à choisir" : piece.getFamille().label()) + " », "
                + "revient " + montant(piece.getPrixRevient()) + ", vente "
                + (piece.getPrixVente() == null ? "à décider" : montant(piece.getPrixVente()));
    }

    /**
     * Le motif de la recherche. Les {@code %} et les {@code _} tapés par l'atelier
     * sont des caractères de référence, pas des jokers : une recherche sur « A_100 »
     * ne doit pas sortir tout le catalogue.
     */
    private static String motifDeRecherche(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "%";
        }
        String echappe = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + echappe.toLowerCase(Locale.ROOT) + "%";
    }
}
