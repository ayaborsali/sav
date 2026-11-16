package tn.higer.sav_backend.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.PieceRechangeDtos.Issue;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportCommit;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportLine;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportPreview;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportSummary;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceRequest;
import tn.higer.sav_backend.model.PieceFamily;
import tn.higer.sav_backend.model.PieceRechange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lecture d'un classeur (ou d'un csv) dans le catalogue des pièces de rechange.
 *
 * Le principe tenu ici, le même que pour l'import des forfaits : <b>un fichier ne
 * remplace jamais la validation humaine</b>. {@link #preview} ne touche aucune
 * table : il dit ce qui serait enregistré, ligne par ligne, et ce qui l'empêche,
 * avec le numéro de ligne tel qu'Excel l'affiche et la raison en français. Ce
 * n'est qu'après que l'atelier a relu l'aperçu que {@link #confirmer} écrit, et
 * l'écriture passe par le même service que la saisie à l'écran — donc par les
 * mêmes refus et le même recalcul des prix.
 *
 * Ce qui n'est <b>jamais</b> lu depuis le fichier : {@code prix_revient} ni
 * {@code prix_vente}. Une colonne « prix de revient » ou « prix de vente » dans un
 * document Higer ou dans le tableur d'un collègue est signalée, pas importée :
 * sinon deux fichiers venus de deux bureaux écriraient deux prix différents sous la
 * même référence, et le devis d'après ne saurait plus lequel a été décidé. Le
 * serveur les refait, toujours, comme pour une saisie à l'écran.
 *
 * Ce qui se lit : les deux <b>coefficients</b>, celui du revient et celui de la
 * vente (« Coefficient de vente », « Coef. vente », « Taux de vente »). Ce sont des
 * taux, pas des résultats — un prix de vente calculé ailleurs ne se rattrape pas,
 * un taux se relit et se corrige.
 *
 * Ce qui est obligatoire dans le fichier : la {@code famille} de la pièce — le
 * « type PR » de l'atelier, une des sept de {@link PieceFamily}. Un document
 * constructeur n'en porte pas toujours : le classeur reçoit alors une colonne
 * « Famille » à remplir, et une ligne qui ne la remplit pas est refusée. Le
 * serveur ne range jamais une pièce à la place de l'atelier, pas même en « Divers »
 * — une étiquette mise par défaut fausse toutes les listes filtrées sur cette
 * famille.
 *
 * Les deux coefficients, eux, peuvent manquer dans le fichier — c'est le cas
 * normal d'un document constructeur, qui donne un prix Ex Works et rien d'autre.
 * Ils sont alors remplacés par les valeurs saisies <b>avant</b> l'aperçu. S'il ne
 * reste rien des deux pour une ligne, elle est refusée : un coefficient inventé par
 * le serveur se retrouve facturé au client.
 */
@Service
public class PieceCatalogImportService {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String FEUILLE_PIECES = "pieces";
    private static final Set<String> FEUILLES_HORS_CATALOGUE = Set.of("notice", "exemple");
    private static final int DECIMALES_DUN_PRIX = 3;
    private static final int DECIMALES_DUN_COEFFICIENT = 4;

    private final DataFormatter formatter = new DataFormatter();

    private final PieceCatalogService catalogue;

    public PieceCatalogImportService(PieceCatalogService catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * Le classeur est lu, compris, rendu — et rien n'est écrit.
     *
     * @param coefficientRevientBrut la valeur à poser sur les lignes dont la colonne de revient est vide ou absente
     * @param coefficientVenteBrut   la valeur à poser sur les lignes dont la colonne de vente est vide ou absente
     */
    public PieceImportPreview preview(MultipartFile file, String coefficientRevientBrut, String coefficientVenteBrut) {
        byte[] bytes = read(file);
        String nom = file.getOriginalFilename() == null ? "le fichier" : file.getOriginalFilename();
        List<Issue> erreurs = new ArrayList<>();
        List<Issue> avertissements = new ArrayList<>();

        BigDecimal defautRevient = coefficient(coefficientRevientBrut, "coefficient de revient", nom, erreurs);
        BigDecimal defautVente = coefficient(coefficientVenteBrut, "coefficient de vente", nom, erreurs);

        List<List<String>> grille;
        if (estCsv(nom)) {
            grille = lireCsv(bytes, nom, erreurs);
        } else {
            grille = lireClasseur(bytes, nom, erreurs, avertissements);
        }
        if (!erreurs.isEmpty()) {
            return new PieceImportPreview(List.of(), erreurs, avertissements);
        }
        return lireLignes(grille, defautRevient, defautVente, nom, erreurs, avertissements);
    }

    /**
     * Enregistrement des lignes relues à l'écran. Chaque ligne suit le chemin de
     * la saisie à l'écran : {@link PieceCatalogService#upsertFromImport}, donc une
     * référence déjà au catalogue est mise à jour au lieu d'être doublée, et le
     * prix de revient se recalcule ici et non du fichier.
     */
    public PieceImportSummary confirmer(PieceImportCommit commit, Jwt jwt) {
        List<PieceImportLine> lignes = commit.lignes() == null ? List.of() : commit.lignes();
        if (lignes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucune ligne à enregistrer : relance l'aperçu du classeur et confirme ce qu'il a compris.");
        }
        int ajoutees = 0;
        int misesAJour = 0;
        List<Issue> refuses = new ArrayList<>();
        for (PieceImportLine ligne : lignes) {
            try {
                if (catalogue.upsertFromImport(ligne, jwt)) {
                    ajoutees++;
                } else {
                    misesAJour++;
                }
            } catch (ResponseStatusException refus) {
                refuses.add(new Issue("Catalogue", ligne.row(), null,
                        "Ligne refusée à l'enregistrement : " + refus.getReason()));
            }
        }
        return new PieceImportSummary(ajoutees, misesAJour, refuses.size(), refuses);
    }

    /**
     * Le classeur vide à remplir : la feuille « Pieces » avec ses entêtes, une
     * feuille « Exemple » qui montre deux lignes, une feuille « Notice » qui dit
     * comment remplir.
     *
     * Les exemples ne sont PAS dans la feuille de saisie : un fichier qui
     * importerait « EXEMPLE-001 » comme pièce réelle empoisonnerait le catalogue
     * et se retrouverait sur un devis. La lecture ignore d'ailleurs les feuilles
     * Notice et Exemple, dans le cas où l'atelier les laisserait traîner.
     */
    public byte[] modele() {
        List<String> entetes = List.of("Référence", "Désignation FR", "Désignation EN", "Famille",
                "Prix Ex Works", "Devise", "Fournisseur", "Coefficient de revient", "Coefficient de vente");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ecrireFeuille(workbook.createSheet("Pieces"), List.of(entetes));
            ecrireFeuille(workbook.createSheet("Exemple"), List.of(
                    entetes,
                    List.of("9.8001.045", "Filtre à gasoil", "Fuel filter", "Mécanique", "86,500", "EUR",
                            "Higer Parts", "1,7200", "1,5000"),
                    List.of("2115U-001", "Plaquette de frein AV", "Front brake pad", "Freinage", "124,380", "EUR",
                            "Higer Parts", "1,6800", "1,4000")));
            ecrireFeuille(workbook.createSheet("Notice"), List.of(
                    List.of("Comment remplir ce classeur"),
                    List.of("1. Écris tes lignes dans la feuille « Pieces », une par pièce. Les entêtes sont reconnus sans accent et sans casse."),
                    List.of("2. « Référence » et « Désignation FR » sont obligatoires. Une ligne sans référence est refusée : deux pièces du même numéro, ce sont deux prix différents chez le client."),
                    List.of("3. « Famille » est obligatoire, et elle ne prend qu'un des sept mots du catalogue : "
                            + PieceFamily.mots() + ". « mecanique », « FREINAGE » et « Boîte de vitesse » se lisent tous ; "
                            + "un autre mot refuse la ligne. Le serveur ne range jamais une pièce à ta place, pas même en « Divers »."),
                    List.of("4. « Prix Ex Works » est le prix annoncé par le fournisseur, dans la devise de sa facture. Trois décimales au plus, positif, sans unité ni symbole dans la case. La colonne « Devise » accepte EUR, USD, TND ou CNY ; vide, elle vaut EUR."),
                    List.of("5. « Coefficient de revient » porte le transport, le dédouanement et la conversion de devise. C'est l'atelier qui le choisit pièce par pièce : le portail n'invente aucun taux."),
                    List.of("6. « Coefficient de vente » porte la marge : prix de vente = prix de revient × coefficient de vente. Les deux coefficients sont obligatoires — colonne du fichier, sinon la valeur saisie avant l'import."),
                    List.of("7. Les DEUX PRIX ne s'écrivent pas ici : le serveur calcule le prix de revient (Ex Works × coefficient de revient) puis le prix de vente (revient × coefficient de vente) à chaque enregistrement. Une colonne « Prix de revient » ou « Prix de vente » est signalée et ignorée."),
                    List.of("8. Un coefficient à 0 est refusé : il ne donne pas une pièce gratuite, il ne donne aucun prix. Le prix de vente ne se remplit jamais de 0 — le premier devis qui citerait la pièce la facturerait gratis."),
                    List.of("9. Une référence déjà au catalogue est mise à jour, elle n'est pas doublée, et son prix de vente est recalculé avec le coefficient de cette ligne. Si elle avait été retirée, l'import la remet en service."),
                    List.of("10. Deux fois la même référence dans le fichier : la dernière ligne gagne, et l'aperçu le dit."),
                    List.of("11. Un coefficient peut rester vide dans le fichier : la valeur saisie avant l'import prend le relais. Sans l'une ni l'autre, la ligne est refusée."),
                    List.of("12. Formats acceptés : .xlsx, .xls, .csv. L'aperçu montre tout avant d'écrire quoi que ce soit.")));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le modèle de classeur n'a pas pu être produit.", e);
        }
    }

    // ------------------------------------------------------------------ lecture du classeur

    /** Une feuille de texte brut : l'entête et les lignes, telles qu'Excel les montre. */
    private static void ecrireFeuille(Sheet sheet, List<List<String>> lignes) {
        for (int r = 0; r < lignes.size(); r++) {
            Row ligne = sheet.createRow(r);
            List<String> valeurs = lignes.get(r);
            for (int c = 0; c < valeurs.size(); c++) {
                ligne.createCell(c).setCellValue(valeurs.get(c));
            }
        }
    }

    /** Les lignes du fichier, mises en grille de texte, la feuille de saisie choisie. */
    private List<List<String>> lireClasseur(byte[] bytes, String nom, List<Issue> erreurs, List<Issue> avertissements) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet choisie = null;
            List<String> vues = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                String nomFeuille = workbook.getSheetName(i);
                vues.add(nomFeuille);
                if (fold(nomFeuille).equals(FEUILLE_PIECES)) {
                    choisie = workbook.getSheetAt(i);
                    break;
                }
                if (choisie == null && !FEUILLES_HORS_CATALOGUE.contains(fold(nomFeuille))) {
                    choisie = workbook.getSheetAt(i); // première feuille qui n'est pas une notice
                }
            }
            if (choisie == null) {
                erreurs.add(new Issue(nom, null, null,
                        "Aucune feuille à lire dans ce classeur (feuilles trouvées : " + String.join(", ", vues)
                                + "). Télécharge le modèle et écris tes pièces dans la feuille « Pieces »."));
                return List.of();
            }
            List<List<String>> grille = new ArrayList<>();
            for (int r = 0; r <= choisie.getLastRowNum(); r++) {
                Row row = choisie.getRow(r);
                List<String> valeurs = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        valeurs.add(text(row.getCell(c)));
                    }
                }
                grille.add(valeurs);
            }
            if (fold(choisie.getSheetName()).equals(FEUILLE_PIECES) && !vues.contains(choisie.getSheetName())) {
                avertissements.add(new Issue(nom, null, null, "La feuille lue n'a pas exactement le nom du modèle."));
            }
            return grille;
        } catch (IOException | RuntimeException e) {
            erreurs.add(new Issue(nom, null, null,
                    "Le fichier n'a pas pu être lu comme un classeur Excel : " + cause(e)
                            + ". Télécharge un .xlsx, un .xls ou un .csv — un fichier renommé à la main ne se lit pas."));
            return List.of();
        }
    }

    // ------------------------------------------------------------------ lecture du csv

    private List<List<String>> lireCsv(byte[] bytes, String nom, List<Issue> erreurs) {
        String texte = decoder(bytes, nom, erreurs);
        List<List<String>> grille = new ArrayList<>();
        if (texte == null) {
            return grille;
        }
        char separateur = separateurDeLaPremiereLigne(texte);
        List<String> ligneCourante = new ArrayList<>();
        StringBuilder valeur = new StringBuilder();
        boolean entreGuillemets = false;
        for (int i = 0; i < texte.length(); i++) {
            char c = texte.charAt(i);
            if (entreGuillemets) {
                if (c == '"') {
                    if (i + 1 < texte.length() && texte.charAt(i + 1) == '"') {
                        valeur.append('"');
                        i++;
                    } else {
                        entreGuillemets = false;
                    }
                } else {
                    valeur.append(c);
                }
                continue;
            }
            if (c == '"') {
                entreGuillemets = true;
            } else if (c == separateur) {
                ligneCourante.add(valeur.toString().trim());
                valeur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texte.length() && texte.charAt(i + 1) == '\n') {
                    i++;
                }
                ligneCourante.add(valeur.toString().trim());
                valeur.setLength(0);
                grille.add(ligneCourante);
                ligneCourante = new ArrayList<>();
            } else {
                valeur.append(c);
            }
        }
        if (valeur.length() > 0 || !ligneCourante.isEmpty()) {
            ligneCourante.add(valeur.toString().trim());
            grille.add(ligneCourante);
        }
        if (grille.isEmpty()) {
            erreurs.add(new Issue(nom, null, null, "Le fichier csv ne contient aucune ligne lisible."));
        }
        return grille;
    }

    /** Le séparateur est celui que la ligne d'entête utilise réellement (Excel français écrit « ; »). */
    private static char separateurDeLaPremiereLigne(String texte) {
        String premiere = texte.lines().findFirst().orElse("");
        char meilleur = ',';
        int compte = -1;
        for (char candidat : new char[]{';', ',', '\t'}) {
            int n = 0;
            for (int i = 0; i < premiere.length(); i++) {
                if (premiere.charAt(i) == candidat) n++;
            }
            if (n > compte) {
                compte = n;
                meilleur = candidat;
            }
        }
        return meilleur;
    }

    /**
     * Un csv exporté d'Excel est souvent en Latin-1, un csv exporté d'un autre
     * logiciel en UTF-8. Un « Filtre à gasoil » mal décodé devient « Filtre  gasoil »
     * et entre au catalogue avec ses points d'interrogation : la repli sur Latin-1
     * est donc une correction, pas une tolérance.
     */
    private static String decoder(byte[] bytes, String nom, List<Issue> erreurs) {
        try {
            java.nio.charset.CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        } catch (RuntimeException e) {
            erreurs.add(new Issue(nom, null, null, "Le fichier csv n'a pas pu être décodé."));
            return null;
        }
    }

    // ------------------------------------------------------------------ lignes

    /**
     * Entête → colonnes, puis chaque ligne du bas. Une colonne inconnue n'est pas
     * une erreur : elle est signalée et ignorée, le fichier garde sa forme.
     */
    private PieceImportPreview lireLignes(List<List<String>> grille, BigDecimal defautRevient,
                                          BigDecimal defautVente, String nom, List<Issue> erreurs,
                                          List<Issue> avertissements) {
        if (grille.isEmpty() || grille.get(0).isEmpty()) {
            erreurs.add(new Issue(nom, 1, null,
                    "Aucune ligne d'entête lisible. Reprends le modèle (.xlsx ou .csv) et garde ses colonnes."));
            return new PieceImportPreview(List.of(), erreurs, avertissements);
        }
        List<String> entete = grille.get(0);
        Set<Integer> occupees = new LinkedHashSet<>();
        Set<Integer> prixDerives = new LinkedHashSet<>();
        int refCol = cueillette(entete, occupees, "reference", "ref", "code", "numeropiece", "partnumber", "pn");
        int designationEnCol = cueillette(entete, occupees, "designationen", "designationanglais",
                "englishdesignation", "designationfrancaiseetanglaise");
        // La famille se cueille tôt : « Type » est un mot que les classeurs mettent
        // aussi sur une colonne de prix ou de fournisseur, et une famille volée par
        // une autre colonne se lit ensuite comme une case vide ligne après ligne.
        int familleCol = cueillette(entete, occupees, "famille", "typepr", "family",
                "category", "categorie", "type", "groupe");
        int coefRevientCol = cueilletteCoefficientRevient(entete, occupees);
        // Le coefficient de vente se cueille lui aussi avant les colonnes de prix :
        // c'est un taux reconnu, pas une colonne à ignorer. Une colonne « Prix de
        // vente » restée libre finira dans les prix dérivés, comme le revient.
        int coefVenteCol = cueilletteCoefficientVente(entete, occupees);
        int prixCol = cueilletteSansVente(entete, occupees, prixDerives, "prixexworks", "exworks", "prixexwork",
                "prixexworkht", "unitprice", "prix", "pu", "montant");
        int designationFrCol = cueillette(entete, occupees, "designationfr", "designation", "libelle",
                "article", "description", "designations");
        int fournisseurCol = cueillette(entete, occupees, "fournisseur", "supplier", "vendor", "origine");
        int deviseCol = cueillette(entete, occupees, "devise", "currency", "monnaie");

        if (refCol < 0) {
            erreurs.add(new Issue(nom, 1, null,
                    "Aucune colonne de référence reconnue. Le modèle attend une colonne « Référence » : c'est elle "
                            + "qui décide si la ligne s'ajoute ou remplace une pièce déjà au catalogue."));
        }
        if (prixCol < 0) {
            erreurs.add(new Issue(nom, 1, null,
                    "Aucune colonne de prix Ex Works reconnue (« Prix Ex Works », « Ex Works », « Prix », « P.U »)."));
        }
        // Pas de colonne « Famille » = tout le classeur refusé, et c'est dit une fois
        // plutôt que ligne après ligne. Le catalogue range chaque pièce, et une pièce
        // entrée sans famille sortirait de toutes les listes filtrées.
        if (familleCol < 0) {
            erreurs.add(new Issue(nom, 1, null,
                    "Aucune colonne de famille reconnue. Ajoute une colonne « Famille » à la première ligne : chaque "
                            + "pièce s'y range dans une seule des sept familles — " + PieceFamily.mots() + "."));
        }
        signaleLesColonnesDePrixDerive(nom, entete, prixDerives, avertissements);
        signaleLesColonnesInconnues(nom, entete, occupees, avertissements, refCol, designationFrCol,
                designationEnCol, familleCol, prixCol, fournisseurCol, coefRevientCol, coefVenteCol, deviseCol);
        if (!erreurs.isEmpty()) {
            return new PieceImportPreview(List.of(), erreurs, avertissements);
        }

        // Deux fois la même référence dans le fichier : la dernière ligne gagne, et
        // l'aperçu le dit au lieu de laisser croire que les deux sont entrées.
        Map<String, Integer> lignesParReference = new LinkedHashMap<>();
        List<Integer> ordre = new ArrayList<>();
        for (int r = 1; r < grille.size(); r++) {
            List<String> row = grille.get(r);
            String reference = case_(row, refCol);
            String designation = case_(row, designationFrCol);
            String prix = case_(row, prixCol);
            if (reference == null && designation == null && prix == null) {
                continue; // ligne vide
            }
            if (reference == null) {
                erreurs.add(new Issue(nom, r + 1, "Référence",
                        "Cette ligne n'a pas de référence : elle ne peut être ni ajoutée ni retrouvée au catalogue."));
                continue;
            }
            Integer dejaVue = lignesParReference.put(fold(reference), r);
            if (dejaVue != null) {
                avertissements.add(new Issue(nom, dejaVue + 1, "Référence",
                        "La référence « " + reference + " » revient ligne " + (r + 1)
                                + " : c'est cette dernière ligne qui sera enregistrée, l'autre est abandonnée."));
                ordre.remove(Integer.valueOf(dejaVue));
            }
            ordre.add(r);
        }

        List<PieceImportLine> lignes = new ArrayList<>();
        for (int r : ordre) {
            List<String> row = grille.get(r);
            // Le refus d'un coefficient s'écrit dans la même liste que les autres :
            // ce qui suit dans la liste dit si la case a été refusée, et la ligne ne
            // se propose alors pas — un taux qu'on ne sait pas lire n'est pas
            // « absent ». Les cases se lisent dans l'ordre du modèle (prix, revient,
            // vente), pour que la première erreur soit celle que l'atelier voit en
            // premier dans son classeur.
            int erreursAvantLaCase = erreurs.size();
            BigDecimal prixExWorks = montantLit(row, prixCol, DECIMALES_DUN_PRIX, "Prix Ex Works", nom, r, erreurs);
            BigDecimal coefficientRevient = coefficientLit(row, coefRevientCol, defautRevient,
                    DECIMALES_DUN_COEFFICIENT, "Coefficient de revient", nom, r, erreurs);
            BigDecimal coefficientVente = coefficientLit(row, coefVenteCol, defautVente,
                    DECIMALES_DUN_COEFFICIENT, "Coefficient de vente", nom, r, erreurs);
            String famille = familleLit(row, familleCol, nom, r, erreurs);
            PieceRequest demande = new PieceRequest(
                    case_(row, refCol),
                    case_(row, designationFrCol),
                    case_(row, designationEnCol),
                    famille,
                    prixExWorks,
                    coefficientRevient,
                    coefficientVente,
                    case_(row, fournisseurCol),
                    deviseLit(row, deviseCol, nom, r, erreurs));
            if (demande.reference() == null || demande.designationFr() == null) {
                if (demande.designationFr() == null) {
                    erreurs.add(new Issue(nom, r + 1, "Désignation FR",
                            "La pièce « " + demande.reference() + " » n'a pas de désignation française : c'est le mot "
                                    + "que le client lira sur le devis."));
                }
                continue;
            }
            if (demande.prixExWorks() == null || demande.coefficientRevient() == null
                    || demande.coefficientVente() == null) {
                continue; // la raison est déjà écrite par la lecture de la case
            }
            if (erreurs.size() > erreursAvantLaCase) {
                continue; // taux de vente illisible : la raison est écrite
            }
            if (demande.devise() == null) {
                continue; // devise inconnue : la raison est déjà écrite par deviseLit
            }
            if (demande.prixExWorks().signum() <= 0) {
                erreurs.add(new Issue(nom, r + 1, "Prix Ex Works",
                        "« " + case_(row, prixCol) + " » : le prix Ex Works doit être positif. Une pièce à zéro "
                                + "dinar n'existe pas, et son prix de revient sortirait à zéro."));
                continue;
            }
            lignes.add(PieceImportLine.of(r + 1, demande, catalogue.existe(demande.reference())));
        }

        if (lignes.isEmpty() && erreurs.isEmpty()) {
            erreurs.add(new Issue(nom, null, null,
                    "Aucune ligne de pièce lisible sous cette entête. Le fichier est peut-être vide, ou ses colonnes "
                            + "ne sont pas celles du modèle."));
        }
        return new PieceImportPreview(lignes, erreurs, avertissements);
    }

    /**
     * La colonne du coefficient de revient, cherchée <b>avant</b> les colonnes de
     * prix et hors de toute colonne qui dit « vente ». Le catalogue ne connaît
     * qu'un coefficient : un classeur qui apporte une colonne « Coefficient de
     * vente » doit la voir signalée et ignorée, jamais reprise comme coefficient
     * de revient — ce serait le prix de la pièce qui changerait de sens sous le
     * même numéro.
     */
    private static int cueilletteCoefficientRevient(List<String> entete, Set<Integer> occupees) {
        for (int c = 0; c < entete.size(); c++) {
            if (occupees.contains(c)) {
                continue;
            }
            String pliee = fold(entete.get(c));
            if (pliee.contains("vente")) {
                continue;
            }
            if (pliee.startsWith("coef")) {
                occupees.add(c);
                return c;
            }
        }
        return -1;
    }

    /**
     * La colonne du coefficient de vente : un mot de taux ET le mot « vente »
     * (« Coefficient de vente », « Coef. vente », « Taux de vente »).
     *
     * Une colonne « Prix de vente » n'est pas retenue ici, et c'est voulu : le prix
     * de vente est un résultat, pas un taux. Elle tombe dans les colonnes de prix
     * dérivés, signalée et ignorée — un classeur d'avant V47 garde sa forme, mais
     * son prix de vente ne rentre pas par la porte de service.
     */
    private static int cueilletteCoefficientVente(List<String> entete, Set<Integer> occupees) {
        for (int c = 0; c < entete.size(); c++) {
            if (occupees.contains(c)) {
                continue;
            }
            String pliee = fold(entete.get(c));
            if (!pliee.contains("vente")) {
                continue;
            }
            if (pliee.startsWith("coef") || pliee.contains("coefficient")
                    || pliee.contains("taux") || pliee.contains("marge")) {
                occupees.add(c);
                return c;
            }
        }
        return -1;
    }

    /** Une colonne qui dit « vente » ou « revient » n'est jamais le prix Ex Works : l'un est une colonne du fournisseur, l'autre se calcule. */
    private static int cueilletteSansVente(List<String> entete, Set<Integer> occupees, Set<Integer> derives,
                                           String... aliases) {
        for (int c = 0; c < entete.size(); c++) {
            String pliee = fold(entete.get(c));
            // Les deux colonnes de coefficient ont déjà été retenues plus haut : seule
            // une colonne de prix restée libre est un prix dérivé à signaler.
            if (!occupees.contains(c) && (pliee.contains("vente") || pliee.contains("revient"))) {
                derives.add(c);
                occupees.add(c); // protégée : jamais reprise comme colonne du prix fournisseur
            }
        }
        return cueillette(entete, occupees, aliases);
    }

    /**
     * Une colonne que le catalogue ne sait pas lire n'est pas une erreur : elle est
     * signalée et ignorée, le fichier garde sa forme. Les cas qui comptent sont les
     * prix que le serveur ne recueille pas — le revient, qui se calcule, et un second
     * prix de vente ou un taux de marge : une seule colonne de prix de vente se lit,
     * la pièce n'a pas vocation à porter deux prix concurrents sous sa référence.
     */
    private static void signaleLesColonnesInconnues(String fichier, List<String> entete, Set<Integer> occupees,
                                                    List<Issue> avertissements, int... retenues) {
        Set<Integer> connues = new LinkedHashSet<>(occupees);
        for (int c : retenues) {
            if (c >= 0) connues.add(c);
        }
        for (int c = 0; c < entete.size(); c++) {
            String nom = entete.get(c);
            if (nom == null || nom.isBlank() || connues.contains(c)) {
                continue;
            }
            avertissements.add(new Issue(fichier, 1, nom,
                    "Colonne « " + nom + " » ignorée : le catalogue ne connaît pas cette case. La ligne entre quand "
                            + "même avec ce que le serveur sait en lire."));
        }
    }

    /**
     * Un prix calculé venu d'un classeur ne rentre pas : le serveur le refait à
     * chaque enregistrement. Sans ce signal, l'atelier croirait avoir importé ses
     * prix, et le devis d'après sortirait un autre chiffre que le sien. Les colonnes
     * de coefficient, elles, ont déjà été retenues ; ce qui reste et qui parle de
     * vente ou de revient est un prix dérivé, pas un taux.
     */
    private static void signaleLesColonnesDePrixDerive(String fichier, List<String> entete, Set<Integer> derives,
                                                       List<Issue> avertissements) {
        for (int c : derives) {
            String nom = entete.get(c);
            if (nom == null || nom.isBlank()) {
                continue;
            }
            avertissements.add(new Issue(fichier, 1, nom,
                    "Colonne « " + nom + " » ignorée : les prix sortent du calcul serveur (prix Ex Works × "
                            + "coefficient de revient, puis prix de revient × coefficient de vente), jamais du "
                            + "fichier. Un prix posé à la main sous la même référence ne serait plus le chiffre que "
                            + "le portail sait recalculer."));
        }
    }

    // ------------------------------------------------------------------ cases

    /** La première colonne dont le nom plié est l'un des synonymes, puis (à défaut) commence par l'un d'eux. */
    private static int cueillette(List<String> entete, Set<Integer> occupees, String... aliases) {
        for (String alias : aliases) {
            String pliee = fold(alias);
            for (int c = 0; c < entete.size(); c++) {
                if (!occupees.contains(c) && fold(entete.get(c)).equals(pliee)) {
                    occupees.add(c);
                    return c;
                }
            }
            for (int c = 0; c < entete.size(); c++) {
                if (!occupees.contains(c) && fold(entete.get(c)).startsWith(pliee)) {
                    occupees.add(c);
                    return c;
                }
            }
        }
        return -1;
    }

    private static String case_(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return null;
        }
        String value = row.get(index);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String text(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    /**
     * Une case d'argent ou de coefficient : « 1 250,50 », « 1.250,50 », « 1250.50 »
     * et « 1250 » se lisent tous. Ce qui ne se lit pas n'est pas deviné — la ligne
     * est refusée avec ce qui a été lu, parce qu'une valeur devinée de travers se
     * facture sur le devis d'après.
     */
    private static BigDecimal montantLit(List<String> row, int index, int decimalesPermises, String quoi,
                                         String nom, int r, List<Issue> erreurs) {
        String brut = index < 0 ? null : (index < row.size() ? row.get(index) : null);
        String canonique = nombre(brut);
        if (canonique == null) {
            if (brut != null && !brut.isBlank()) {
                erreurs.add(new Issue(nom, r + 1, quoi,
                        "« " + brut.trim() + " » ne se lit pas comme un nombre pour « " + quoi
                                + " ». Écris-le en chiffres, sans unité ni symbole."));
            } else {
                erreurs.add(new Issue(nom, r + 1, quoi, "La case « " + quoi + " » est vide : sans elle, la pièce n'a pas de prix."));
            }
            return null;
        }
        return scaled(canonique, quoi, nom, r, decimalesPermises, erreurs);
    }

    /**
     * Un coefficient — revient ou vente : la colonne du fichier d'abord, la valeur
     * saisie avant l'import ensuite. Rien des deux ? le serveur n'invente pas un
     * taux, et une pièce sans coefficient n'a pas de prix : la ligne part en erreur
     * avec ce qui manque.
     */
    private static BigDecimal coefficientLit(List<String> row, int index, BigDecimal defaut, int decimalesPermises,
                                             String quoi, String nom, int r, List<Issue> erreurs) {
        String brut = index < 0 ? null : (index < row.size() ? row.get(index) : null);
        String canonique = nombre(brut);
        if (canonique != null) {
            BigDecimal valeur = scaled(canonique, quoi, nom, r, decimalesPermises, erreurs);
            if (valeur != null && valeur.signum() <= 0) {
                erreurs.add(new Issue(nom, r + 1, quoi,
                        "« " + canonique + " » ne peut pas servir de coefficient : il doit être positif, "
                                + "sinon la pièce sort à un prix nul."));
                return null;
            }
            return valeur;
        }
        if (brut != null && !brut.isBlank()) {
            erreurs.add(new Issue(nom, r + 1, quoi,
                    "« " + brut.trim() + " » ne se lit pas comme un coefficient pour « " + quoi
                            + " ». Écris un nombre simple, par exemple 1,7200."));
            return null;
        }
        if (defaut != null) {
            return defaut;
        }
        erreurs.add(new Issue(nom, r + 1, quoi,
                "La case « " + quoi + " » est vide dans le fichier et aucune valeur n'a été saisie avant l'import. "
                        + "Saisis-la en haut de l'écran d'import, ou remplis la colonne."));
        return null;
    }

    private static BigDecimal scaled(String canonique, String quoi, String nom, int r, int decimalesPermises,
                                     List<Issue> erreurs) {
        BigDecimal valeur = new BigDecimal(canonique);
        if (valeur.stripTrailingZeros().scale() > decimalesPermises) {
            erreurs.add(new Issue(nom, r + 1, quoi,
                    "« " + canonique + " » dépasse les " + decimalesPermises
                            + " décimales admises pour « " + quoi + " »."));
            return null;
        }
        return valeur;
    }

    private static BigDecimal coefficient(String brut, String quoi, String nom, List<Issue> erreurs) {
        String canonique = nombre(brut);
        if (canonique == null) {
            if (brut != null && !brut.isBlank()) {
                erreurs.add(new Issue(nom, null, quoi,
                        "Le « " + quoi + " » saisi avant l'import (« " + brut.trim()
                                + " ») ne se lit pas comme un nombre."));
            }
            return null;
        }
        BigDecimal valeur = new BigDecimal(canonique);
        if (valeur.signum() <= 0) {
            erreurs.add(new Issue(nom, null, quoi,
                    "Le « " + quoi + " » saisi avant l'import doit être positif."));
            return null;
        }
        if (valeur.stripTrailingZeros().scale() > DECIMALES_DUN_COEFFICIENT) {
            erreurs.add(new Issue(nom, null, quoi,
                    "Le « " + quoi + " » se donne au 1/10 000ᵉ près, pas davantage."));
            return null;
        }
        return valeur;
    }

    /**
     * La devise du prix Ex Works. Case vide = EUR, comme la saisie à l'écran.
     * Une devise hors liste est une erreur de ligne, pas un avertissement : réétiqueter
     * « GBP » en EUR ferait changer le prix d'un devis de monnaie sans le dire.
     */
    /**
     * La famille de la pièce, rendue sous le nom que le service relit (« FREINAGE »).
     *
     * Case vide ou mot hors liste = ligne refusée, jamais rangée en « Divers » pour
     * faire passer le classeur : « Divers » est un choix de l'atelier, pas le rebut
     * d'un mot que le serveur n'a pas reconnu. Une pièce mal rangée disparaît des
     * listes filtrées, qui sont précisément là pour la retrouver.
     */
    private static String familleLit(List<String> row, int index, String nom, int r, List<Issue> erreurs) {
        String brut = case_(row, index);
        if (brut == null) {
            erreurs.add(new Issue(nom, r + 1, "Famille",
                    "La case « Famille » est vide : une pièce se range dans une des sept familles du catalogue ("
                            + PieceFamily.mots() + "). Sans elle, la ligne ne se retrouve plus quand on cherche "
                            + "par famille."));
            return null;
        }
        PieceFamily lue = PieceFamily.lit(brut);
        if (lue == null) {
            erreurs.add(new Issue(nom, r + 1, "Famille",
                    "« " + brut + " » n'est pas une famille du catalogue : choisis parmi " + PieceFamily.mots()
                            + ". La casse et les accents ne comptent pas (« freinage », « FREINAGE » se lisent), "
                            + "un mot inventé non plus."));
            return null;
        }
        return lue.name();
    }

    private static String deviseLit(List<String> row, int index, String nom, int r, List<Issue> erreurs) {
        String brut = case_(row, index);
        if (brut == null) {
            return PieceRechange.DEVISE_PAR_DEFAUT;
        }
        String propre = brut.toUpperCase(Locale.ROOT);
        if (!PieceRechange.DEVISES.contains(propre)) {
            erreurs.add(new Issue(nom, r + 1, "Devise",
                    "« " + brut + " » n'est pas une devise connue : choisis EUR, USD, TND ou CNY. "
                            + "Une devise inventée étiquetterait le prix Ex Works de travers."));
            return null;
        }
        return propre;
    }

    /** « 1 250,50 », « 1.250,50 », « 1250.50 », « 1250 » → un nombre canonique ; le reste n'est pas déviné. */
    private static String nombre(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^0-9.,-]", "");
        if (cleaned.isEmpty()) return null;
        int decimal = Math.max(cleaned.lastIndexOf(','), cleaned.lastIndexOf('.'));
        if (decimal < 0) return cleaned;
        String partieEntiere = cleaned.substring(0, decimal).replaceAll("[.,]", "");
        String decimales = cleaned.substring(decimal + 1);
        if (decimales.isEmpty() || !decimales.chars().allMatch(Character::isDigit)) return null;
        return (partieEntiere.isEmpty() ? "0" : partieEntiere) + "." + decimales;
    }

    private static String fold(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase();
    }

    // ------------------------------------------------------------------ outils

    private byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucun fichier reçu : choisis le classeur dans ton poste.");
        }
        String nom = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(nom.endsWith(".xlsx") || nom.endsWith(".xls") || nom.endsWith(".csv"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + (nom.isEmpty() ? "le fichier" : nom) + " » n'est pas un fichier que le catalogue sait lire. "
                            + "Les formats acceptés sont .xlsx, .xls et .csv — exporte ta liste depuis Excel plutôt que "
                            + "de renommer un document.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le fichier dépasse 5 Mo. Un catalogue de pièces tient dans un classeur bien plus petit : ne garde "
                            + "que la feuille des pièces.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier n'a pas pu être lu.", e);
        }
    }

    private static boolean estCsv(String nom) {
        return nom != null && nom.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private static String cause(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
