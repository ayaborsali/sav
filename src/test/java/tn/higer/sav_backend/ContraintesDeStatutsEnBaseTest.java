package tn.higer.sav_backend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import tn.higer.sav_backend.model.ClientType;
import tn.higer.sav_backend.model.DocumentCategory;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PieceFamily;
import tn.higer.sav_backend.model.PartRequestState;
import tn.higer.sav_backend.model.PartSupplier;
import tn.higer.sav_backend.model.PieceType;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SavPriority;
import tn.higer.sav_backend.model.VehicleStatus;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Les mots que la base accepte sont-ils bien les mots que le Java écrit ?
 *
 * Une colonne d'état se range en base comme une `character varying` tenue par une
 * contrainte CHECK. Ces contraintes-là sont plus anciennes que Flyway : elles viennent
 * de la toute première base, et aucune migration ne les a jamais reprises. Résultat
 * vérifié le 28/10 : `SavCaseStatus` comptait huit états, la contrainte n'en connaissait
 * que six. Un technicien qui appuyait sur « j'arrive » faisait donc partir un 500 — et
 * les 546 tests ne disaient rien, parce qu'ils bouchonnent les dépôts et n'envoient
 * jamais un vrai UPDATE à PostgreSQL.
 *
 * Ce test ne se lance que là où la base existe (même garde que le démarrage de contexte)
 * et relit la contrainte telle qu'elle est écrite en base. Il compare les deux listes
 * sans rien excuser : un état ajouté côté Java sans migration, comme un mot resté en
 * base après une suppression, se voient ici et pas sur l'écran de quelqu'un.
 */
@SpringBootTest
class ContraintesDeStatutsEnBaseTest {

    /** Les valeurs d'un enum, dans l'ordre où le Java les écrit. */
    private static Set<String> noms(Class<? extends Enum<?>> enumClass) {
        Set<String> noms = new LinkedHashSet<>();
        for (Object constante : enumClass.getEnumConstants()) {
            noms.add(((Enum<?>) constante).name());
        }
        return noms;
    }

    @Autowired
    private JdbcTemplate base;

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test relit une contrainte dans PostgreSQL.");
    }

    /** Les mots que la contrainte énumère, tels qu'ils sortent de `pg_get_constraintdef`. */
    private Set<String> motsEnBase(String table, String colonne) {
        String definition = base.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint"
                        + " where conrelid = ?::regclass and contype = 'c'"
                        + " and pg_get_constraintdef(oid) like ?",
                String.class, table, "%(" + colonne + ")::text = ANY%");
        Set<String> mots = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([A-Za-z0-9_]+)'").matcher(definition == null ? "" : definition);
        while (m.find()) {
            mots.add(m.group(1));
        }
        return mots;
    }

    private void verifier(String table, String colonne, Class<? extends Enum<?>> enumClass) {
        Set<String> enBase = motsEnBase(table, colonne);
        Set<String> enJava = noms(enumClass);
        // Le message nomme les deux écarts : ce que la base refuse et ce qu'elle tolère en trop.
        Set<String> quiManquent = new LinkedHashSet<>(enJava);
        quiManquent.removeAll(enBase);
        Set<String> quiRestent = new LinkedHashSet<>(enBase);
        quiRestent.removeAll(enJava);
        if (!quiManquent.isEmpty() || !quiRestent.isEmpty()) {
            throw new AssertionError(table + "." + colonne + " : la contrainte de la base n'est plus"
                    + " la liste des états du Java. Manquant(s) en base " + quiManquent
                    + " — absent(s) du Java " + quiRestent
                    + ". En base : " + enBase + ". En Java (" + enumClass.getSimpleName() + ") : " + enJava + ".");
        }
    }

    @Test
    void laBaseConnaitTousLesStatutsDuDossier() {
        // Les deux qui ont manqué : ON_SITE et RETURNED, écrits par le dossier qui suit
        // une sortie. Sans eux, « j'arrive » ne passait plus en réel.
        verifier("sav_cases", "status", SavCaseStatus.class);
    }

    @Test
    void lesAutresColonnesEtatNontPasBouge() {
        verifier("sav_cases", "type", SavCaseType.class);
        verifier("sav_cases", "priority", SavPriority.class);
        verifier("clients", "type", ClientType.class);
        verifier("vehicles", "status", VehicleStatus.class);
        verifier("documents", "category", DocumentCategory.class);
        verifier("maintenance_items", "status", MaintenanceStatus.class);
        verifier("maintenance_items", "type", MaintenanceType.class);
    }

    /**
     * La pièce demandée sur une sortie a cinq états, et le serveur les écrit tous :
     * « À commander » à la demande, « Commandée » au geste du guichet, « Partielle » ou
     * « Livrée » à la réception du magasin, « Posée » au second passage. Un sixième
     * ajouté côté Java sans passer par V31 ferait refuser la ligne par PostgreSQL —
     * exactement le 500 de V30, joué sur l'écran du technicien cette fois.
     */
    @Test
    void laPieceDemandeeConnaitTousSesEtats() {
        verifier("intervention_part_requests", "state", PartRequestState.class);
    }

    /**
     * Les deux mots neufs d'une ligne de devis, posés par V36 : la nature de ce qui est
     * chiffré (main-d'œuvre, pièce, déplacement) et qui fournit la pièce. `PartSupplier`
     * s'écrivait « SAPPHIR » avec deux P dans V36 ; V37 reprend le CHECK, parce que ce
     * mot sort dans le JSON du devis et se recopie dans le sélecteur de l'écran — un mot
     * mal écrit dans une colonne d'argent se propage plus vite qu'il ne se corrige.
     *
     * Le piège reste celui de V30 : un enum qui écrit un mot que la base ne connaît pas
     * part en `23514` au premier enregistrement, et les 26 tests du service ne le voient
     * pas, parce qu'ils bouchonnent le dépôt.
     */
    @Test
    void lesLignesDuDevisOntLeursNaturesEtLeursFournisseurs() {
        verifier("maintenance_details", "nature", LineNature.class);
        verifier("maintenance_details", "supplied_by", PartSupplier.class);
    }

    /**
     * Les trois papiers ont leurs mots à eux : le devis « envoyé », « accepté », « refusé »
     * (V36), l'ordre « à faire », « en cours », « terminé », et le bon de livraison « signé »
     * depuis V39. La contrainte de `maintenance_items.status` doit tenir les neuf, sinon
     * « Marquer signé » s'écrit en 500 et le client repart avec un bus que le portail croit
     * encore à l'atelier.
     */
    @Test
    void laBaseConnaitLesNeufEtatsDesPapiers() {
        assertEquals(9, noms(MaintenanceStatus.class).size());
        verifier("maintenance_items", "status", MaintenanceStatus.class);
    }

    /**
     * Les sept familles du catalogue, posées par V44. Le mot écrit en base est le nom
     * de l'enum, pas le libellé accentué que l'écran montre — « Électricité » se range
     * ELECTRICITE. Un huitième rayon ajouté côté Java sans migration se refuserait par
     * PostgreSQL au premier enregistrement, et la case vide se refuserait aussi : la
     * colonne est NOT NULL depuis que l'atelier a tranché « Non, obligatoire ».
     */
    @Test
    void laFamilleDuCatalogueConnaitSeptMotsEnBase() {
        assertEquals(7, noms(PieceFamily.class).size());
        verifier("piece_rechanges", "famille", PieceFamily.class);
    }

    /**
     * Les deux mots du « type » de la pièce, posés par V45. La colonne est vide chez
     * beaucoup de catalogues importés (le classeur n'a pas de colonne Type) : le
     * `IS NULL` de la CHECK ne doit pas tromper la lecture, d'où le patron regex plutôt
     * que la forme `::text = ANY` des colonnes obligatoires.
     */
    @Test
    void leTypeDuCatalogueConnaitDeuxMotsEnBase() {
        assertEquals(2, noms(PieceType.class).size());
        String definition = base.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint"
                        + " where conrelid = 'piece_rechanges'::regclass and contype = 'c'"
                        + " and pg_get_constraintdef(oid) like '%type_piece%'",
                String.class);
        Set<String> mots = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([A-Za-z0-9_]+)'").matcher(definition == null ? "" : definition);
        while (m.find()) {
            mots.add(m.group(1));
        }
        assertEquals(noms(PieceType.class), mots,
                "piece_rechanges.type_piece : la CHECK de la base n'est plus la liste des deux mots du Java. En base : "
                        + mots + ".");
    }

    /**
     * Un statut que le serveur écrit pour un dossier doit exister en base. La liste est
     * celle de `SavCaseService` et de `suivreSortie` ; elle est écrite ici pour qu'un
     * statut neuf oblige à passer par les deux.
     */
    @Test
    void lesStatutsEcritsParLeServeurPassentTousLaContrainte() {
        Set<String> ecritsParLeServeur = new LinkedHashSet<>(Arrays.asList(
                "OPEN", "IN_PROGRESS", "ON_SITE", "PENDING_PARTS", "APPROVED", "REJECTED", "RETURNED", "CLOSED"));
        assert ecritsParLeServeur.equals(noms(SavCaseStatus.class));
        verifier("sav_cases", "status", SavCaseStatus.class);
    }
}
