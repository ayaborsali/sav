package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le pont Java -> Python des papiers de l'atelier : mêmes règles que le contrat et le bon
 * d'intervention (script absent = 503 qui nomme le fichier, dépendance Python absente = 503 qui
 * nomme la commande, panne du script = 502), et surtout le vrai script doit rendre les TROIS
 * papiers. Un devis et un bon de livraison n'ont pas la même page : le premier porte ses totaux,
 * le second ses cases de réception et aucune ligne de prix. Si l'une des deux casse, l'atelier
 * n'a plus de papier à garder chez lui.
 */
class MaintenancePdfServiceTest {

    private static final String SCRIPT = "./scripts/render_maintenance_pdf.py";

    private static MaintenancePdfService service(String python, String script) {
        return new MaintenancePdfService(python, script, 20);
    }

    /** Un devis complet, dans la forme exacte que MaintenanceDocumentService produit. */
    private static Map<String, Object> planDevis() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "Devis");
        plan.put("brand", "Saphir Véhicules et Automobiles");
        plan.put("brand_lines", List.of("Route de Tunis, Ben Arous", "RC : —", "Matricule fiscal : —"));
        plan.put("subtitle", "Devis — le travail chiffré que le client accepte ou refuse");
        plan.put("number", "DV-2026-0007");
        plan.put("status_label", "État : Accepté");
        plan.put("date_label", "Édité le 04/11/2026");
        plan.put("badge", "Devis chiffré en dinars tunisiens (DT).");
        plan.put("boxes", List.of(
                Map.of("heading", "Le papier", "rows",
                        List.of(List.of("Nature", "Devis"), List.of("Numéro", "DV-2026-0007"),
                                List.of("Date du papier", "02/11/2026"),
                                List.of("État", "Accepté"),
                                List.of("Technicien nommé", "ayaborsali"))),
                Map.of("heading", "Le client et le bus", "rows",
                        List.of(List.of("Client", "SNTRI"),
                                List.of("Châssis (VIN)", "LTSD45H32D0000123"),
                                List.of("Compteur de la fiche du bus", "48120 km")))));
        plan.put("tables", List.of(Map.of(
                "heading", "Les lignes du devis, chiffrées en dinars tunisiens (DT)",
                "columns", List.of("Référence", "Désignation", "Nature", "Fourni par",
                        "Quantité", "P.U. HT (DT)", "Montant HT (DT)"),
                "weights", List.of(0.9, 2.2, 0.9, 0.8, 0.6, 0.65, 0.65),
                "rows", List.of(List.of("F-10", "Filtre à huile", "Pièce", "Saphir", "2",
                        "11,500", "23,000")),
                "note", "Aucune ligne sur ce devis.")));
        plan.put("totals", List.of(List.of("Total HT", "120,000"),
                List.of("TVA 19 %", "22,800"), List.of("Total TTC", "142,800")));
        plan.put("texts", List.of(Map.of("heading", "Ce qui est écrit sur le papier",
                "body", "Vidange et remplacement du filtre.")));
        plan.put("signatures", List.of(
                Map.of("name", "SNTRI", "label", "Le client — devis lu, chiffré et accepté"),
                Map.of("name", "Saphir Véhicules et Automobiles",
                        "label", "Saphir — devis établi par l'atelier")));
        plan.put("footer", "Saphir Véhicules et Automobiles — document généré automatiquement.");
        return plan;
    }

    /** Le bon de livraison : pas de totaux, trois cases de plus, le compteur des deux côtés. */
    private static Map<String, Object> planBon() {
        Map<String, Object> plan = new LinkedHashMap<>(planDevis());
        plan.put("kind", "Bon de livraison");
        plan.put("number", "BL-2026-0094");
        plan.put("status_label", "État : Signé");
        plan.put("subtitle", "Bon de livraison — restitution du bus et des pièces");
        plan.put("badge", "Bon de livraison signé — aucun montant n'y figure (V39).");
        plan.remove("totals");
        plan.put("boxes", List.of(
                Map.of("heading", "Le papier", "rows", List.of(List.of("Nature", "Bon de livraison"),
                        List.of("Numéro", "BL-2026-0094"))),
                Map.of("heading", "La réception du bus", "rows",
                        List.of(List.of("Signataire", "Moez Ben Salah"),
                                List.of("À quel titre", "Directeur d'exploitation"),
                                List.of("Signé le", "04/11/2026"),
                                List.of("Pièces remplacées remises", "oui"),
                                List.of("Compteur à la restitution", "48500 km"))),
                Map.of("heading", "Le compteur, des deux côtés de la case", "rows",
                        List.of(List.of("Fiche du bus", "48120 km"),
                                List.of("Écrit par", "Bon de livraison (atelier@saphir.tn)"),
                                List.of("Plus haut relevé", "48500 km")))));
        plan.put("tables", List.of(Map.of(
                "heading", "Pièces et fluides fournis par Saphir, rendus avec le bus — sans prix (V39)",
                "columns", List.of("Référence", "Désignation", "Quantité"),
                "weights", List.of(1.0, 2.6, 0.6),
                "rows", List.of(List.of("F-10", "Filtre à huile", "2")),
                "note", "Rien n'a été fourni par Saphir sur ce papier.")));
        plan.put("texts", List.of(Map.of("heading", "Réserves du client en reprenant le bus",
                "body", "Rien n'a été écrit pour cette partie.")));
        plan.put("signatures", List.of(
                Map.of("name", "Moez Ben Salah", "label", "Le client — bus repris, lu et approuvé"),
                Map.of("name", "Saphir Véhicules et Automobiles",
                        "label", "Le chef d'atelier — restitution validée")));
        return plan;
    }

    /** L'ordre de travail : le temps se lit ligne par ligne, jamais le prix. */
    private static Map<String, Object> planOrdre() {
        Map<String, Object> plan = new LinkedHashMap<>(planDevis());
        plan.put("kind", "Ordre de travail");
        plan.put("number", "OR-2026-0011");
        plan.put("status_label", "État : Terminé");
        plan.put("subtitle", "Ordre de travail — ce que l'atelier exécute");
        plan.put("badge", "Papier interne : l'ordre décrit le travail et ne le facture pas (V38).");
        plan.remove("totals");
        plan.put("tables", List.of(Map.of(
                "heading", "Le travail de l'atelier, ligne par ligne — aucune colonne de prix (V38)",
                "columns", List.of("Référence", "Opération", "Nature", "Prévu", "Fait", "Heures réelles"),
                "weights", List.of(0.9, 2.6, 0.9, 0.7, 0.6, 0.8),
                "rows", List.of(List.of("OP-1", "Vidange moteur", "Main-d'œuvre", "0,9 h", "Fait", "1,25")),
                "note", "Aucune opération sur cet ordre.")));
        return plan;
    }

    @Test
    void scriptIntrouvable_renvoie503EtNommeLeFichier() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("python", "./scripts/inexistant.py").render(planDevis()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode(),
                "Un script absent est une configuration, pas une panne du papier");
        assertTrue(erreur.getReason().contains("scripts/inexistant.py"), erreur.getReason());
    }

    @Test
    void pythonAbsent_renvoie503EtNommeLaCommande() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("python-dans-le-leur", SCRIPT).render(planDevis()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("app.python.executable"),
                "L'administrateur doit savoir quelle clé régler : " + erreur.getReason());
    }

    @Test
    void enveloppe_duScriptDevientUnCodeHttp() {
        MaintenancePdfService on = service("python", SCRIPT);

        ReflectionTestUtils.invokeMethod(on, "parseEnvelope", "{\"ok\": true, \"bytes\": 900}", 0, "");

        ResponseStatusException config = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"config\", \"error\": \"reportlab est absent\"}", 1, ""));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, config.getStatusCode());
        assertTrue(config.getReason().contains("reportlab"), config.getReason());

        ResponseStatusException casse = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"internal\", \"error\": \"mise en page échouée\"}", 1, ""));
        assertEquals(HttpStatus.BAD_GATEWAY, casse.getStatusCode());
    }

    /** Un PDF de 0 octet venu du script est une anomalie, pas un document vide. */
    @Test
    void pdf_vide_renvoie502() throws Exception {
        Path vide = Files.createTempFile("maintenance-test-", ".pdf");
        try {
            ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                    () -> ReflectionTestUtils.invokeMethod(service("python", SCRIPT), "readPdf", vide));
            assertEquals(HttpStatus.BAD_GATEWAY, erreur.getStatusCode());
        } finally {
            Files.deleteIfExists(vide);
        }
    }

    /** Le numéro manque : la mise en page refuse (502), elle n'imprime pas un papier anonyme. */
    @Test
    void unPlanSansNumero_seRefuse() {
        Map<String, Object> sansNumero = new LinkedHashMap<>(planDevis());
        sansNumero.remove("number");

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("python", SCRIPT).render(sansNumero));

        assertEquals(HttpStatus.BAD_GATEWAY, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("incompletes"), erreur.getReason());
    }

    /** Vrai test d'intégration : le plan traverse le ProcessBuilder et reportlab écrit le fichier. */
    @Test
    void leVraiScriptProduitLeDevisChiffre() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");

        assertEstUnPdf(service("python", SCRIPT).render(planDevis()));
    }

    @Test
    void leVraiScriptProduitLOrdreDeTravail() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");

        assertEstUnPdf(service("python", SCRIPT).render(planOrdre()));
    }

    @Test
    void leVraiScriptProduitLeBonDeLivraison() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");

        assertEstUnPdf(service("python", SCRIPT).render(planBon()));
    }

    private static void assertEstUnPdf(byte[] pdf) {
        assertTrue(pdf.length > 1000, "Un papier d'une page fait plusieurs kilo-octets : " + pdf.length);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "Signature PDF attendue");
        String queue = new String(pdf, pdf.length - 64, 64, StandardCharsets.ISO_8859_1);
        assertTrue(queue.contains("%%EOF"), "Le fichier doit être terminé, pas tronqué : " + queue);
    }

    private static boolean reportlabPresent() {
        try {
            Process probe = new ProcessBuilder("python", "-c", "import reportlab").start();
            boolean ok = probe.waitFor(20, TimeUnit.SECONDS) && probe.exitValue() == 0;
            probe.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}
