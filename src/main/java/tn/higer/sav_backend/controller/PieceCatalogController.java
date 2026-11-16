package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceFamilyOption;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportCommit;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportPreview;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportSummary;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceRequest;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceResponse;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.PieceCatalogImportService;
import tn.higer.sav_backend.service.PieceCatalogService;

import java.util.List;

/**
 * Le catalogue des pièces de rechange, sous-écran « Gestion PR » de Maintenance.
 *
 * Lecture : tout compte connecté, par la règle générique {@code GET /api/**}, mais
 * les montants ne sortent que pour un compte qui a droit aux chiffres commerciaux
 * ({@link Access#seesCommercialFigures}) — la valeur est absente de la réponse
 * JSON, pas seulement grisée à l'écran.
 *
 * Écriture : admin et gestionnaire SAV, par la règle générique déjà posée sur
 * POST/PUT/DELETE de {@code /api/**}. Aucune règle nouvelle ici : un catalogue que
 * le guichet seul remplit est exactement ce que cette règle sait déjà faire.
 *
 * <b>Ce que le module Devis trouvera ici.</b> {@code GET /api/pieces?search=} est
 * la route d'autocomplétion de la future ligne de devis : elle répond par
 * référence et désignations, sans qu'on ait à connaître l'identifiant de la pièce,
 * et elle est paginée parce qu'un catalogue Higer ne se charge pas entier pour
 * faire défiler une liste. Le jour où le devis s'écrira, il devra <b>copier</b> la
 * référence et la désignation dans la ligne de devis, et y poser le prix que
 * l'atelier décide ce jour-là — les colonnes existent déjà
 * ({@code maintenance_details.reference / label / unit_price}) — pour qu'un prix
 * remanié six mois plus tard ne réécrive pas un devis que le client a accepté. Le
 * catalogue fournit pour ça l'identifiant, la référence, les deux désignations, la
 * <b>famille</b> et le <b>prix de vente</b> : la copie part de ce prix comme prix
 * unitaire de départ, l'atelier reste libre de le corriger sur la ligne, et
 * l'identifiant de la pièce reste gardé pour la trace. Une pièce sans prix de vente
 * ne se placera pas sur un devis sans que ça se dise — jamais de 0 par défaut. Le
 * catalogue, lui, ne bouge pas quand un devis se chiffre. Rien de tout cela n'est
 * codé aujourd'hui : le module Devis attend.
 */
@RestController
@RequestMapping("/api/pieces")
public class PieceCatalogController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final int TAILLE_DE_PAGE = 25;
    private static final int TAILLE_MAXIMALE_DE_PAGE = 200;

    private final PieceCatalogService catalogue;
    private final PieceCatalogImportService importateur;

    public PieceCatalogController(PieceCatalogService catalogue, PieceCatalogImportService importateur) {
        this.catalogue = catalogue;
        this.importateur = importateur;
    }

    /**
     * Le catalogue, cherché par référence ou par désignation, et filtrable par
     * famille. Le tri est serveur (référence croissante) : l'atelier retrouve une
     * pièce par son numéro, et « ce qui est Freinage » se compte en base, pas à
     * l'écran sur la première page.
     *
     * {@code famille} accepte les sept mots de l'atelier dans n'importe quelle casse
     * et sans accents ; un mot hors liste est refusé en les nommant, parce qu'une
     * liste vidée par une faute de frappe ressemble à un catalogue vide.
     */
    @GetMapping
    public Page<PieceResponse> list(@RequestParam(required = false) String search,
                                    @RequestParam(required = false) String famille,
                                    @RequestParam(required = false, defaultValue = "0") int page,
                                    @RequestParam(required = false, defaultValue = "" + TAILLE_DE_PAGE) int size,
                                    Authentication auth) {
        int bornee = Math.min(Math.max(size, 1), TAILLE_MAXIMALE_DE_PAGE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), bornee, Sort.by(Sort.Direction.ASC, "reference"));
        return catalogue.search(search, famille, pageable, Access.seesCommercialFigures(auth));
    }

    /**
     * Les sept familles du catalogue, code et mot français. L'écran ne les recopie
     * pas : la liste fermée vit dans la base (la CHECK de V44) et dans ce service, et
     * un huitième rayon qui arrive par une migration doit se proposer tout seul.
     *
     * Se lit avant {@code /{id}} : le chemin littéral gagne sur le patron, « familles »
     * n'est donc pas pris pour un identifiant de pièce.
     */
    @GetMapping("/familles")
    public List<PieceFamilyOption> familles() {
        return catalogue.familles();
    }

    /**
     * Les deux types du catalogue — Mécanique, Électrique — dans le même format que les
     * familles. Le classeur d'import n'a pas de colonne Type : ce sont les deux mots que
     * l'atelier classe à l'écran, et c'est le serveur qui les dicte.
     *
     * Chemin littéral, donc déclaré avant {@code /{id}}.
     */
    @GetMapping("/types")
    public List<PieceFamilyOption> types() {
        return catalogue.types();
    }

    @GetMapping("/{id}")
    public PieceResponse get(@PathVariable String id, Authentication auth) {
        return catalogue.get(id, Access.seesCommercialFigures(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PieceResponse create(@Valid @RequestBody PieceRequest request, @AuthenticationPrincipal Jwt jwt,
                                Authentication auth) {
        return catalogue.create(request, jwt, Access.seesCommercialFigures(auth));
    }

    /** Remplace la ligne telle que la demande la dit — comme le {@code PUT} des dossiers du portail. */
    @PutMapping("/{id}")
    public PieceResponse update(@PathVariable String id, @Valid @RequestBody PieceRequest request,
                                @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return catalogue.update(id, request, jwt, Access.seesCommercialFigures(auth));
    }

    /**
     * Retirer du catalogue, pas effacer : une pièce déjà recopiée sur un devis
     * accepté ou sur une ligne d'ordre doit rester relisible par sa référence.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        catalogue.deactivate(id, jwt);
    }

    /**
     * Aperçu d'un classeur : rien n'est écrit à cette étape.
     *
     * Les deux coefficients ne se devinent pas : quand la colonne manque ou reste
     * vide dans le fichier, c'est la valeur saisie ici qui la remplace. Sans l'une
     * ni l'autre, la ligne part en erreur au lieu de recevoir un coefficient inventé
     * par le serveur.
     *
     * Les deux prix, eux, n'ont pas de « valeur à défaut » : ils sortent du calcul
     * serveur à l'enregistrement. Une colonne de prix dans le classeur est signalée
     * et ignorée.
     */
    @PostMapping("/import")
    public PieceImportPreview preview(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String coefficientRevient,
                                      @RequestParam(required = false) String coefficientVente) {
        return importateur.preview(file, coefficientRevient, coefficientVente);
    }

    /** Enregistrement des lignes relues à l'écran : ajouter ou mettre à jour, référence par référence. */
    @PostMapping("/import/confirmer")
    public PieceImportSummary confirmer(@Valid @RequestBody PieceImportCommit commit,
                                        @AuthenticationPrincipal Jwt jwt) {
        return importateur.confirmer(commit, jwt);
    }

    /** Le classeur vide à remplir : les colonnes attendues, deux lignes d'exemple, une page de notice. */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/modele-import")
    public ResponseEntity<byte[]> modele() {
        byte[] bytes = importateur.modele();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele-pieces-rechange.xlsx\"")
                .body(bytes);
    }
}
