package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tn.higer.sav_backend.dto.DocumentDtos.DocumentResponse;
import tn.higer.sav_backend.dto.DocumentDtos.DocumentSummary;
import tn.higer.sav_backend.dto.VehicleDtos.VehicleResponse;
import tn.higer.sav_backend.dto.VehicleDtos.VehicleSummary;
import tn.higer.sav_backend.model.Document;
import tn.higer.sav_backend.model.MileageOrigin;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ContractRepository;
import tn.higer.sav_backend.repository.DocumentRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.VehicleMileageService;
import tn.higer.sav_backend.service.VinDetectionService;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Vérifie que les listes paginées n'emportent pas le binaire (blob) :
 * GET /api/vehicles omet plateImage, GET /api/documents omet fileData.
 * Le binaire reste présent sur les endpoints de détail.
 * Vérifie aussi le masquage serveur des prix pour le rôle consultation.
 */
@ExtendWith(MockitoExtension.class)
class ProjectionTest {

    private static final String BLOB = "data:image/png;base64,SECRETBLOBNEDOITPASAPPARAITRE";
    private static final BigDecimal PRIX = new BigDecimal("185000.00");

    @Mock
    private VehicleRepository vehicles;

    @Mock
    private ContractRepository contracts;

    @Mock
    private DocumentRepository documents;

    @Mock
    private AuditService audit;

    @Mock
    private VinDetectionService vinDetection;

    /** La porte du compteur n'intervient pas dans les lectures testées ici. */
    @Mock
    private VehicleMileageService compteur;

    private static Authentication role(String authority) {
        return new UsernamePasswordAuthenticationToken("x@y.tn", null,
                List.of(new SimpleGrantedAuthority(authority)));
    }

    private static Vehicle vehiculeAvecImage() {
        Vehicle v = new Vehicle();
        v.setId("veh-1");
        v.setVin("VIN0001");
        v.setRegistration("AB-123-CD");
        v.setClientId("cli-1");
        v.setPlateImage(BLOB);
        v.setUnitPriceHt(PRIX);
        return v;
    }

    private static Document documentAvecFichier() {
        Document d = new Document();
        d.setId("doc-1");
        d.setTitle("Carte grise");
        d.setFileData(BLOB);
        return d;
    }

    /** Aucun champ de l'instance ne doit porter la valeur du blob. */
    private static boolean porteLeBlob(Object instance) throws Exception {
        for (Field field : instance.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            if (BLOB.equals(field.get(instance))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void listeVehicules_neTransportePasPlateImage() throws Exception {
        Pageable pageable = PageRequest.of(0, 20);
        when(vehicles.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(vehiculeAvecImage())));
        VehicleController controller = new VehicleController(vehicles, contracts, audit, vinDetection, compteur);

        Page<VehicleSummary> page = controller.list(pageable, role("ROLE_ADMIN"));
        VehicleSummary summary = page.getContent().get(0);

        assertEquals(1, page.getTotalElements());
        // La projection ne déclare même pas le champ, et l'instance ne porte aucun blob.
        assertFalse(Arrays.stream(VehicleSummary.class.getRecordComponents())
                        .anyMatch(c -> c.getName().equals("plateImage")),
                "VehicleSummary ne doit pas exposer plateImage");
        assertFalse(porteLeBlob(summary), "La liste ne doit pas charrier le blob");
    }

    @Test
    void detailVehicule_conservePlateImage() {
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(vehiculeAvecImage()));
        VehicleController controller = new VehicleController(vehicles, contracts, audit, vinDetection, compteur);

        VehicleResponse detail = controller.get("veh-1", role("ROLE_GESTIONNAIRE_SAV"));
        assertEquals(BLOB, detail.plateImage(), "Le détail doit conserver l'image de plaque");
        assertEquals(PRIX, detail.unitPriceHt(), "Un gestionnaire voit le prix");
    }

    /**
     * Phase G : le compteur voyage avec ce qui l'a écrit. L'atelier juge une valeur
     * contestée en lisant d'où elle vient, sans avoir à retrouver la sortie.
     */
    @Test
    void leCompteurSortAvecSonOrigineEtSonAuteur() {
        Vehicle v = vehiculeAvecImage();
        v.setMileage(10_120L);
        v.setMileageOrigin(MileageOrigin.RELEVE_TECHNICIEN);
        v.setMileageRecordedAt(LocalDateTime.of(2026, 10, 18, 8, 30));
        v.setMileageRecordedBy("Ali Trabelsi");
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(v));
        VehicleController controller = new VehicleController(vehicles, contracts, audit, vinDetection, compteur);

        VehicleResponse detail = controller.get("veh-1", role("ROLE_GESTIONNAIRE_SAV"));

        assertEquals("relevé par le technicien sur une sortie", detail.mileageOrigin());
        assertEquals("Ali Trabelsi", detail.mileageRecordedBy());
        assertEquals(LocalDateTime.of(2026, 10, 18, 8, 30), detail.mileageRecordedAt());
    }

    @Test
    void consultation_listeEtDetail_masquesSurLePrix() {        Pageable pageable = PageRequest.of(0, 20);
        when(vehicles.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(vehiculeAvecImage())));
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(vehiculeAvecImage()));
        VehicleController controller = new VehicleController(vehicles, contracts, audit, vinDetection, compteur);

        assertNull(controller.list(pageable, role("ROLE_CONSULTATION")).getContent().get(0).unitPriceHt(),
                "La liste ne doit pas divulguer le prix au rôle consultation");
        assertNull(controller.get("veh-1", role("ROLE_CONSULTATION")).unitPriceHt(),
                "Le détail ne doit pas divulguer le prix au rôle consultation");
    }

    @Test
    void listeDocuments_neTransportePasFileData() throws Exception {
        Pageable pageable = PageRequest.of(0, 20);
        when(documents.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(documentAvecFichier())));
        DocumentController controller = new DocumentController(documents, audit);

        Page<DocumentSummary> page = controller.list(pageable);
        DocumentSummary summary = page.getContent().get(0);

        assertEquals(1, page.getTotalElements());
        assertFalse(Arrays.stream(DocumentSummary.class.getRecordComponents())
                        .anyMatch(c -> c.getName().equals("fileData")),
                "DocumentSummary ne doit pas exposer fileData");
        assertFalse(porteLeBlob(summary), "La liste ne doit pas charrier le fichier base64");
    }

    @Test
    void detailDocument_conserveFileData() {
        when(documents.findById("doc-1")).thenReturn(Optional.of(documentAvecFichier()));
        DocumentController controller = new DocumentController(documents, audit);

        DocumentResponse detail = controller.get("doc-1");
        assertTrue(BLOB.equals(detail.fileData()), "Le détail doit conserver le fichier base64");
    }
}
