package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Contract;
import tn.higer.sav_backend.model.Vehicle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contenu du contrat de maintenance tel que le reçoit le script de mise en page : les montants
 * viennent du calcul serveur (HT + TVA = TTC), la somme en toutes lettres est fabriquée à partir
 * du total, les clauses vides retombent sur la rédaction standard — qui décrit des prestations
 * d'atelier sur des bus confiés, jamais une vente — et le rôle consultation ne reçoit
 * aucun prix : ni ligne de total, ni colonne de forfait, ni somme en lettres.
 */
class ContractDocumentServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 15);

    private static ContractClauses clauses() {
        return new ContractClauses(60, 24, 100000, "Tribunal de Commerce de Tunis", "Saphir Véhicules et Automobiles");
    }

    private static ContractDocumentService service() {
        return new ContractDocumentService(clauses(), "Zone Industrielle, Ariana", "B0000000",
                "1234567/A/M/000", "M. Le Directeur", 19);
    }

    private static Contract contrat() {
        Contract c = new Contract();
        c.setId("ctr-1");
        c.setNumber("CT-HIG-2026-001");
        c.setContractDate(DATE);
        c.setClientId("cli-1");
        c.setPaymentTerms("30 jours virement");
        c.setDeliveryAddress("Depot Ariana");
        return c;
    }

    private static Client client() {
        Client cl = new Client();
        cl.setId("cli-1");
        cl.setName("Transports du Sahel");
        cl.setAddress("Route de Sousse");
        cl.setCity("Sousse");
        cl.setCountry("Tunisie");
        cl.setTradeRegister("B1234567");
        cl.setTaxId("9999999/F/M/000");
        return cl;
    }

    private static Vehicle ligne(String vin, String prix) {
        Vehicle v = new Vehicle();
        v.setId("veh-" + vin);
        v.setVin(vin);
        v.setRegistration("AB-123-CD");
        v.setModel("Confort");
        v.setYear(2026);
        v.setClientId("cli-1");
        v.setCommissioningDate(DATE);
        v.setWarrantyEndDate(LocalDate.of(2028, 10, 15));
        v.setUnitPriceHt(new BigDecimal(prix));
        return v;
    }

    private static Map<String, Object> plan(boolean figures, Vehicle... lines) {
        return normalise(service().payload(contrat(), client(), List.of(lines), figures));
    }

    /** Le séparateur de milliers français peut être une espace fine ou insécable : on normalise. */
    private static Map<String, Object> normalise(Map<String, Object> payload) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        payload.forEach((key, value) -> copy.put(key, net(value)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static <T> T net(T value) {
        if (value instanceof String text) {
            return (T) text.replace(' ', ' ').replace(' ', ' ');
        }
        if (value instanceof List<?> items) {
            return (T) items.stream().map(ContractDocumentServiceTest::net).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), net(v)));
            return (T) copy;
        }
        return value;
    }

    @Test
    void entete_numerosDateEtParties() {
        Map<String, Object> payload = plan(true);

        assertEquals("CT-HIG-2026-001", payload.get("number"));
        assertEquals("Date : 15/10/2026", payload.get("date_label"));
        assertEquals("Contrat de maintenance de véhicules", payload.get("subtitle"));
        assertTrue(String.valueOf(payload.get("footer")).contains("deux exemplaires originaux"));
    }

    @Test
    void totaux_calculsCoteServeur_etSommeEnLettresAccordeeAuTTC() {
        Map<String, Object> payload = plan(true, ligne("VIN1", "100000.00"), ligne("VIN2", "150000.00"));
        String rows = String.valueOf(payload.get("totals"));

        assertTrue(rows.contains("250 000,000 DT"), "Total HT : " + rows);
        assertTrue(rows.contains("47 500,000 DT"), "TVA 19 % : " + rows);
        assertTrue(rows.contains("297 500,000 DT"), "TTC = HT + TVA arrondi : " + rows);
        assertTrue(String.valueOf(payload.get("totals")).contains(
                        "deux cent quatre-vingt-dix-sept mille cinq cents dinars"),
                "La somme en lettres doit dire le TTC (297 500), pas le HT : " + rows);
    }

    @Test
    void ligneTva_suivantLaConfiguration() {
        ContractDocumentService neuf = new ContractDocumentService(clauses(), "Ariana", "B0", "9", "", 0.19);
        Map<String, Object> payload = normalise(
                neuf.payload(contrat(), client(), List.of(ligne("VIN1", "1000")), true));

        String rows = String.valueOf(payload.get("totals"));
        assertTrue(rows.contains("TVA 19 %"), "0.19 et 19 doivent dire la même chose : " + rows);
        assertFalse(rows.contains("0.19"), rows);
    }

    @Test
    void consultation_aucunPrixNeSortDuServeur() {
        Map<String, Object> payload = plan(false, ligne("VIN1", "100000.00"));
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");

        assertEquals(List.of(), totals.get("rows"), "Aucune ligne de total ne quitte le serveur");
        assertEquals("", totals.get("words"), "Pas davantage de somme en toutes lettres");
        String annexe = String.valueOf(payload.get("annex"));
        assertFalse(annexe.contains("Forfait"), "La colonne du forfait disparaît aussi : " + annexe);
        String tout = String.valueOf(payload);
        assertFalse(tout.contains("Total TTC"), tout);
        assertFalse(tout.contains("100 000,000"), tout);
        assertFalse(tout.contains("dinars"), "Le pied de page ne parle plus de montants : " + tout);
        assertTrue(tout.contains("CT-HIG-2026-001"), "Le numéro reste lisible");
        assertTrue(tout.contains("Article 1"));
        assertTrue(tout.contains("VIN1"));
    }

    @Test
    void annexe_uneLigneParBusEtUnMessageQuandIlNyEnAucun() {
        Map<String, Object> payload = plan(true, ligne("VIN1", "100000.00"), ligne("VIN2", "150000.00"));
        String annexe = String.valueOf(payload.get("annex"));

        assertTrue(annexe.contains("VIN1") && annexe.contains("VIN2"), annexe);
        assertTrue(annexe.contains("AB-123-CD"), annexe);
        assertTrue(annexe.contains("100 000,000 DT"), annexe);
        assertTrue(annexe.contains("15/10/2026") && annexe.contains("15/10/2028"),
                "Mise en circulation et fin garantie se lisent : " + annexe);
        assertTrue(annexe.contains("Véhicules entretenus"),
                "L'annexe porte les bus confiés, pas une marchandise vendue : " + annexe);
        assertTrue(String.valueOf(plan(true).get("annex")).contains("Aucun véhicule rattaché"),
                "Annexe vide : c'est dit, pas laissé blanc");
    }

    @Test
    void clausesVides_redactionStandardAppliquee() {
        String clauses = String.valueOf(plan(true).get("clauses"));

        assertTrue(clauses.contains("Saphir Véhicules et Automobiles"), "L'objet standard nomme le prestataire");
        assertTrue(clauses.contains("Transports du Sahel"), "et le client qui confie ses bus");
        assertTrue(clauses.contains("maintenance préventive et corrective"), clauses);
        assertTrue(clauses.contains("60 jours ouvrables"), "Le délai vient de la configuration");
        assertTrue(clauses.contains("24 mois"));
        assertTrue(clauses.contains("100 000 kilomètres"));
        assertTrue(clauses.contains("Tribunal de Commerce de Tunis"));
        assertFalse(clauses.contains("Article 6"), "Pas de clauses particulières par défaut");
    }

    @Test
    void redactionStandard_neParlePasDeVente() {
        String clauses = String.valueOf(plan(true).get("clauses"));

        assertFalse(clauses.contains("acheteur"), clauses);
        assertFalse(clauses.contains("vendeur"), clauses);
        assertFalse(clauses.contains("vente"), clauses);
        assertTrue(clauses.contains("Article 2 — Prestations et délais d'intervention"), clauses);
        assertTrue(clauses.contains("Article 3 — Garantie des travaux"), clauses);
        assertTrue(clauses.contains("Article 4 — Pièces déposées et propriété"), clauses);
    }

    @Test
    void laSaisieRemplaceLaRedactionStandard() {
        Contract c = contrat();
        c.setDeliveryClause("Les revisions se font au depot du client, le camion venant les chercher.");
        c.setSpecialTerms("Releve mensuel du compteur kilometrique par le client.");
        String clauses = String.valueOf(
                normalise(service().payload(c, client(), List.of(), true)).get("clauses"));

        assertTrue(clauses.contains("Les revisions se font au depot du client, le camion venant les chercher."));
        assertFalse(clauses.contains("jours ouvrables"), "La clause saisie évince le standard");
        assertTrue(clauses.contains("Article 6"), "Les clauses particulières n'existent que si saisies");
    }

    @Test
    void identificationJuridique_desDeuxParties() {
        String parties = String.valueOf(plan(true).get("parties"));

        assertTrue(parties.contains("RC : B1234567"), "RC du client");
        assertTrue(parties.contains("Matricule fiscal : 9999999/F/M/000"), parties);
        assertTrue(parties.contains("RC : B0000000"), "RC du prestataire");
        assertTrue(parties.contains("Le prestataire") && parties.contains("Le client"), parties);
    }

    @Test
    void identificationAbsente_seLitCommeNonRenseignee() {
        Client cl = client();
        cl.setTradeRegister(null);
        cl.setTaxId("   ");
        String parties = String.valueOf(
                normalise(service().payload(contrat(), cl, List.of(), true)).get("parties"));

        assertTrue(parties.contains("RC : —"), "Une absence doit se lire, pas disparaître : " + parties);
        assertTrue(parties.contains("Matricule fiscal : —"), parties);
    }

    @Test
    void uneSaisieNeSePerdPasDansLePlan() {
        Client cl = client();
        cl.setName("Transports & Fils <succ>");
        Contract c = contrat();
        c.setWarrantyClause("Garantie « inclusive » pour le client averti & précautionneux");
        Map<String, Object> payload = normalise(service().payload(c, cl, List.of(), true));

        assertTrue(String.valueOf(payload.get("parties")).contains("Transports & Fils <succ>"),
                "Le texte arrive tel quel : c'est la mise en page qui échappe, pas ici");
        assertTrue(String.valueOf(payload.get("clauses")).contains("Garantie « inclusive »"),
                "Les accents et guillemets du contrat traversent intacts");
    }

    @Test
    void signatures_prestataireEtClientNommes() {
        String signatures = String.valueOf(plan(true).get("signatures"));

        assertTrue(signatures.contains("Saphir Véhicules et Automobiles"), signatures);
        assertTrue(signatures.contains("Le prestataire — M. Le Directeur"), signatures);
        assertTrue(signatures.contains("Le client (lu et approuvé)"), signatures);
    }

    @Test
    void clauseObjet_seContracteSansFauteUnOuPlusieursVehicules() {
        String un = clauses().objectStandard("Transports du Sahel", 1);
        String plusieurs = clauses().objectStandard("Transports du Sahel", 6);

        assertTrue(un.contains("la maintenance préventive et corrective du véhicule désigné à l'annexe"), un);
        assertTrue(un.contains("assurée par Saphir Véhicules et Automobiles pour Transports du Sahel"), un);
        assertTrue(un.contains("qui lui confie ce véhicule"), un);
        assertTrue(plusieurs.contains("la maintenance préventive et corrective des 6 véhicules désignés à l'annexe"),
                plusieurs);
        assertTrue(plusieurs.contains("qui lui confie ces véhicules"), plusieurs);
        assertFalse(plusieurs.contains("du véhicule désigné"), plusieurs);
        // Le document est signé tel quel : « de le » et « de les » sont des fautes, pas des variantes.
        assertFalse(un.contains("de le "), un);
        assertFalse(plusieurs.contains("de les "), plusieurs);
    }

    @Test
    void zoneBlancRetombeSurLeStandard() {
        String standard = clauses().deliveryStandard();
        assertEquals(standard, ContractClauses.effective(null, standard));
        assertEquals(standard, ContractClauses.effective("   ", standard));
        assertEquals("Texte saisi", ContractClauses.effective("  Texte saisi  ", standard));
    }

    @Test
    void sommeEnLettres_reglesFrancaises() {
        assertEquals("quatre-vingts dinars", ContractDocumentService.amountInWords(new BigDecimal("80")));
        assertEquals("soixante et onze dinars", ContractDocumentService.amountInWords(new BigDecimal("71")));
        assertEquals("quatre-vingt-dix dinars", ContractDocumentService.amountInWords(new BigDecimal("90")));
        assertEquals("cent mille dinars", ContractDocumentService.amountInWords(new BigDecimal("100000")));
        assertEquals("quatre cent mille dinars",
                ContractDocumentService.amountInWords(new BigDecimal("400000")));
        assertEquals("deux mille un dinars", ContractDocumentService.amountInWords(new BigDecimal("2001")));
        assertEquals("un million de dinars", ContractDocumentService.amountInWords(new BigDecimal("1000000")));
        // « cent » ne prend la marque du pluriel que s'il termine le nombre : 297 500 finit sur
        // « cinq cents », alors que 400 000 s'écrit « quatre cent mille ».
        assertEquals("deux cent quatre-vingt-dix-sept mille cinq cents dinars",
                ContractDocumentService.amountInWords(new BigDecimal("297500")));
        assertEquals("un dinar et cinq cents millimes",
                ContractDocumentService.amountInWords(new BigDecimal("1.500")));
        assertEquals("zéro dinar", ContractDocumentService.amountInWords(BigDecimal.ZERO));
    }

    @Test
    void auDelaDuMilliard_onRetombeSurLeChiffre() {
        String words = ContractDocumentService.amountInWords(new BigDecimal("1000000000000"));

        assertTrue(words.contains("dinars tunisiens"), words);
        assertFalse(words.contains("billion"), "On n'invente pas le nom d'un ordre de grandeur");
    }
}
