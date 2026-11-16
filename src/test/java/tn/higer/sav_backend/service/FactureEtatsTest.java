package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import tn.higer.sav_backend.dto.FactureDtos.AFacturerDto;
import tn.higer.sav_backend.dto.FactureDtos.EncaissementDto;
import tn.higer.sav_backend.dto.FactureDtos.EncaissementRequest;
import tn.higer.sav_backend.dto.FactureDtos.FactureEmissionRequest;
import tn.higer.sav_backend.dto.FactureDtos.FactureLineDto;
import tn.higer.sav_backend.dto.FactureDtos.FactureResponse;
import tn.higer.sav_backend.dto.FactureDtos.FactureSummary;
import tn.higer.sav_backend.model.Encaissement;
import tn.higer.sav_backend.model.FactureStatus;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L'état d'une facture se calcule, il ne se stocke pas — et ces essais le prouvent
 * sans base, à toutes les dates. Le partage est sans chevauchement : payée si tout
 * est rentré, partielle si une partie est rentrée (même échue), impayée si l'échéance
 * est dépassée sans rien de rentré, émise sinon.
 *
 * La seconde moitié épingle les noms des composantes des DTO : ce sont les clés du
 * JSON, donc le contrat de l'écran Angular ({@code core/types.ts}). Les renommer d'un
 * seul côté ne casse ni le serveur (200 OK) ni la compilation Angular — la liste
 * s'affiche vide chez le guichet, le défaut du 04/11. Un écart se voit ici.
 */
class FactureEtatsTest {

    private static final LocalDate AUJOURDHUI = LocalDate.of(2026, 11, 10);

    private static Encaissement versement(double montant) {
        Encaissement e = new Encaissement();
        e.setAmount(montant);
        return e;
    }

    @Test
    void tout_rentre_est_payee() {
        assertEquals(FactureStatus.PAYEE, FactureEtats.etat(416.50, 416.50, AUJOURDHUI.plusDays(30), AUJOURDHUI));
        // Une tolérance d'un demi-centime absorbe l'arrondi des sommes de doubles.
        assertEquals(FactureStatus.PAYEE, FactureEtats.etat(416.499, 416.50, null, AUJOURDHUI));
    }

    @Test
    void une_partie_rentree_est_partielle_meme_si_echue() {
        assertEquals(FactureStatus.PARTIELLE, FactureEtats.etat(100.0, 416.50, AUJOURDHUI.plusDays(30), AUJOURDHUI));
        // Le versement partiel est un fait : il prime sur le retard, qui se lit à côté.
        assertEquals(FactureStatus.PARTIELLE, FactureEtats.etat(100.0, 416.50, AUJOURDHUI.minusDays(5), AUJOURDHUI));
        assertTrue(FactureEtats.enRetard(FactureStatus.PARTIELLE, AUJOURDHUI.minusDays(5), AUJOURDHUI));
    }

    @Test
    void echeance_depassee_sans_rien_est_impayee() {
        assertEquals(FactureStatus.IMPAYEE, FactureEtats.etat(0.0, 416.50, AUJOURDHUI.minusDays(1), AUJOURDHUI));
        assertTrue(FactureEtats.enRetard(FactureStatus.IMPAYEE, AUJOURDHUI.minusDays(1), AUJOURDHUI));
    }

    @Test
    void pas_echue_sans_rien_est_emise() {
        assertEquals(FactureStatus.EMISE, FactureEtats.etat(0.0, 416.50, AUJOURDHUI.plusDays(10), AUJOURDHUI));
        assertEquals(FactureStatus.EMISE, FactureEtats.etat(0.0, 416.50, null, AUJOURDHUI));
        assertFalse(FactureEtats.enRetard(FactureStatus.EMISE, AUJOURDHUI.plusDays(10), AUJOURDHUI));
    }

    @Test
    void le_jour_de_l_echeance_n_est_pas_encore_un_retard() {
        assertEquals(FactureStatus.EMISE, FactureEtats.etat(0.0, 416.50, AUJOURDHUI, AUJOURDHUI));
        assertFalse(FactureEtats.enRetard(FactureStatus.EMISE, AUJOURDHUI, AUJOURDHUI));
    }

    @Test
    void une_facture_payee_n_est_jamais_en_retard() {
        assertFalse(FactureEtats.enRetard(FactureStatus.PAYEE, AUJOURDHUI.minusDays(30), AUJOURDHUI));
    }

    @Test
    void le_restant_du_ne_descend_pas_sous_zero() {
        assertEquals(316.50, FactureEtats.restantDu(100.0, 416.50), 0.0001);
        assertEquals(0.0, FactureEtats.restantDu(500.0, 416.50), 0.0001);
        assertEquals(416.50, FactureEtats.restantDu(0.0, 416.50), 0.0001);
    }

    @Test
    void la_somme_encaissee_arrondit_au_centime() {
        assertEquals(300.0, FactureEtats.sommeEncaissee(List.of(versement(100.0), versement(200.0))), 0.0001);
        assertEquals(0.0, FactureEtats.sommeEncaissee(List.of()), 0.0001);
    }

    // ------------------------------------------------------------------ le contrat de l'écran

    private static List<String> composantes(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(r -> r.getName()).toList();
    }

    @Test
    void les_cles_json_de_la_facture_sont_celles_que_l_ecran_lit() {
        assertEquals(List.of("id", "number", "blId", "blNumber", "devisId", "devisNumber", "clientId",
                        "clientName", "vehicleId", "vehicleRegistration", "issuedOn", "dueOn", "totalHT",
                        "totalTVA", "totalTTC", "montantEncaisse", "restantDu", "etat", "etatLabel",
                        "enRetard", "createdBy", "notes", "lines", "encaissements"),
                composantes(FactureResponse.class));
        assertEquals(List.of("id", "number", "blNumber", "clientId", "clientName", "vehicleId",
                        "vehicleRegistration", "issuedOn", "dueOn", "totalTTC", "montantEncaisse",
                        "restantDu", "etat", "etatLabel", "enRetard"),
                composantes(FactureSummary.class));
        assertEquals(List.of("blId", "blNumber", "clientId", "clientName", "vehicleId",
                        "vehicleRegistration", "signedOn", "joursDepuisSignature", "devisNumber",
                        "montantAFacturer"),
                composantes(AFacturerDto.class));
        assertEquals(List.of("reference", "label", "quantity", "unitPrice", "lineTotal", "nature", "suppliedBy"),
                composantes(FactureLineDto.class));
        assertEquals(List.of("id", "amount", "paidOn", "mode", "recordedBy", "note"),
                composantes(EncaissementDto.class));
        assertEquals(List.of("blId", "dueOn"), composantes(FactureEmissionRequest.class));
        assertEquals(List.of("amount", "mode", "note"), composantes(EncaissementRequest.class));
    }
}