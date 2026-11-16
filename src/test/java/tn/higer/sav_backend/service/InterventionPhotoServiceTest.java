package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.PhotoDtos.PhotoResponse;
import tn.higer.sav_backend.dto.PhotoDtos.ProofStatusDto;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPhoto;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * La preuve photo est ce qui rend le contrôle crédible : elle est donc testée sur
 * ses règles dures, pas sur son affichage. L'empreinte vient des octets reçus, le
 * fichier quitte la requête pour le disque, et une sortie clôturée ne s'illustre
 * plus sauf décision d'un administrateur.
 */
@ExtendWith(MockitoExtension.class)
class InterventionPhotoServiceTest {

    @Mock private InterventionPhotoRepository photos;
    @Mock private InterventionService interventions;
    @Mock private AuditService audit;

    @TempDir Path storage;

    private static final String ITV = "itv-1";

    // ------------------------------------------------------------------ fixtures

    /**
     * Le service EXIF est réel, avec un script volontairement absent : c'est ce
     * cas-là qui doit rester favorable à l'atelier (photo acceptée, heure de
     * l'appareil inconnue), et il ne peut pas être testé avec une simulation.
     */
    private InterventionPhotoService service() {
        return service(8_000_000L);
    }

    private InterventionPhotoService service(long maxBytes) {
        return new InterventionPhotoService(photos, interventions,
                new PhotoExifService("python", "./script-exif-inexistant.py", 1), audit,
                storage.toString(), maxBytes);
    }

    private static Intervention at(InterventionStatus status) {
        Intervention i = new Intervention();
        i.setId(ITV);
        i.setNumber("DP-2026-001");
        i.setStatus(status);
        return i;
    }

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static MultipartFile jpeg(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/jpeg", bytes);
    }

    private static InterventionPhoto stored(String sha256, String relativePath) {
        InterventionPhoto p = new InterventionPhoto();
        p.setId("pht-1");
        p.setInterventionId(ITV);
        p.setAngle(PhotoAngle.BEFORE);
        p.setSha256(sha256);
        p.setFilePath(relativePath);
        p.setContentType("image/jpeg");
        return p;
    }

    // ------------------------------------------------------------------ versement

    @Test
    void leServeurCalculeLEmpreinteEtEcritLeFichierSurLeDisque() throws Exception {
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.ON_SITE));
        when(photos.findByInterventionIdOrderByUploadedAtAsc(ITV)).thenReturn(List.of());

        PhotoResponse response = service().upload(ITV, jpeg("IMG_0231.jpg", new byte[] {1, 2, 3}),
                "before", "carrosserie avant", jwt(), auth("TECHNICIEN"));

        ArgumentCaptor<InterventionPhoto> saved = ArgumentCaptor.forClass(InterventionPhoto.class);
        verify(photos).save(saved.capture());
        InterventionPhoto p = saved.getValue();
        assertEquals(64, p.getSha256().length(), "l'empreinte n'est pas un SHA-256 complet");
        assertEquals(ITV, p.getInterventionId());
        assertEquals(PhotoAngle.BEFORE, p.getAngle());
        // L'heure de l'appareil n'a pas pu être lue : c'est celle du serveur qui parle.
        assertNull(p.getTakenAt());
        assertNotNull(p.getUploadedAt());
        assertEquals("chef@saphir.tn", p.getActorEmail());
        // Relative à l'adresse de l'API : l'écran la préfixe de son /api, pas le serveur.
        assertEquals("/intervention-photos/" + p.getId() + "/content", response.url());
        assertEquals("before", response.angle());

        Path written = storage.resolve(p.getFilePath());
        assertTrue(Files.isRegularFile(written), "l'image n'est pas écrite sur le disque : " + written);
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(written));
    }

    @Test
    void unFichierHorsPhotoNeVautPasUnePreuve() {
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.ON_SITE));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service().upload(ITV,
                new MockMultipartFile("file", "facture.pdf", "application/pdf", new byte[] {7}),
                "before", null, jwt(), auth("CHEF_ATELIER")));
        assertTrue(e.getReason().contains("JPEG"), e.getReason());
        verifyNoInteractions(photos);
    }

    @Test
    void laMemePhotoVerseDeuxFoisNeComptePasCommeDeuxPreuves() {
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.ON_SITE));
        byte[] bytes = new byte[] {9, 9, 9};
        when(photos.findByInterventionIdOrderByUploadedAtAsc(ITV))
                .thenReturn(List.of(stored(sha256(bytes), "itv-1/abc-IMG.jpg")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service()
                .upload(ITV, jpeg("IMG.jpg", bytes), "after", null, jwt(), auth("TECHNICIEN")));
        assertTrue(e.getReason().contains("déjà versé"), e.getReason());
        verify(photos, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void uneSortieClotureeGeleSesPreuvesPourLeChefAtelier() {
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.CLOSED));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service()
                .upload(ITV, jpeg("IMG.jpg", new byte[] {4}), "before", null, jwt(), auth("CHEF_ATELIER")));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getReason().contains("clôturée"), e.getReason());
    }

    @Test
    void lAdministrateurSeulPeutEncoreVerserApresCloture() {
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.CLOSED));
        when(photos.findByInterventionIdOrderByUploadedAtAsc(ITV)).thenReturn(List.of());

        PhotoResponse response = service().upload(ITV, jpeg("IMG.jpg", new byte[] {5}),
                "before", "cliché retrouvé", jwt(), auth("ADMIN"));

        assertNotNull(response.id());
        // Et la trace dit qui, parce que l'immunité de la fiche clôturée est une décision.
        ArgumentCaptor<String> journal = ArgumentCaptor.forClass(String.class);
        verify(interventions).event(eq(ITV), any(), any(), any(),
                journal.capture());
        assertTrue(journal.getValue().contains("avant intervention"), journal.getValue());
    }

    @Test
    void unAngleInconnuEstRefuseAvecLaListeDesAngles() {
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.ON_SITE));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service()
                .upload(ITV, jpeg("IMG.jpg", new byte[] {6}), "plaque", null, jwt(), auth("TECHNICIEN")));
        assertTrue(e.getReason().contains("mileage"), e.getReason());
        verifyNoInteractions(photos);
    }

    @Test
    void uneImageTropLourdeEstRefuseeAvantEcriture() {
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.ON_SITE));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(10L).upload(ITV, jpeg("IMG.jpg", new byte[5000]),
                        "before", null, jwt(), auth("TECHNICIEN")));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatusCode());
        assertTrue(e.getReason().contains("recadré"), e.getReason());
    }

    /**
     * Les deux clichés de contrôle demandés au technicien — la jauge et le travail
     * en cours — se versent comme les autres. Ils ne remplacent pas la preuve :
     * « avant » et « après » restent les seuls exigés pour valider une prime.
     */
    @Test
    void laJaugeEtLeTravailEnCoursSeVersent() throws Exception {
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.ON_SITE));
        when(photos.findByInterventionIdOrderByUploadedAtAsc(ITV)).thenReturn(List.of());

        assertEquals("fuel", service().upload(ITV, jpeg("jauge.jpg", new byte[] { 7 }),
                "fuel", "niveau au départ", jwt(), auth("TECHNICIEN")).angle());
        assertEquals("during", service().upload(ITV, jpeg("travail.jpg", new byte[] { 8 }),
                "during", null, jwt(), auth("TECHNICIEN")).angle());
    }

    @Test
    void unAngleInconnuEstRefuseAvecLaListeComplete() {
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.ON_SITE));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().upload(ITV, jpeg("clip.mp4", new byte[] { 1 }),
                        "video", null, jwt(), auth("TECHNICIEN")));
        assertTrue(e.getReason().contains("fuel"), e.getReason());
        assertTrue(e.getReason().contains("during"), e.getReason());
    }

    // --------------------------------------------------------------------- lecture

    @Test
    void ceQuiManqueEstDitEnFrancaisEtNommeLAngle() {
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(false);
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.AFTER)).thenReturn(true);

        ProofStatusDto proof = service().proof(ITV);

        assertFalse(proof.ok());
        assertEquals(List.of("before"), proof.missing());
        assertTrue(proof.message().contains("avant intervention"), proof.message());
        assertTrue(proof.message().contains("prime"), proof.message());
    }

    @Test
    void uneSortieCompleteNaucunePreuveManquante() {
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.AFTER)).thenReturn(true);

        ProofStatusDto proof = service().proof(ITV);

        assertTrue(proof.ok());
        assertTrue(proof.missing().isEmpty());
    }

    @Test
    void unCheminHorsDossierDeStockageNeLitPasLeDisque() {
        when(photos.findById("pht-1")).thenReturn(Optional.of(stored("aa", "../../windows/win.ini")));
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.CLOSED));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().content("pht-1", auth("CHEF_ATELIER"), jwt()));
        // Le lien est lu sous contrôle : une valeur trafiquée dans la base ne fait pas
        // lire un fichier du serveur à un utilisateur connecté.
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertTrue(e.getReason().contains("absent du disque"), e.getReason());
    }

    @Test
    void laPhotoSeRelitDepuisLeDisquePourUnUtilisateurAutorise() throws Exception {
        Path dir = storage.resolve(ITV);
        Files.createDirectories(dir);
        Files.write(dir.resolve("IMG.jpg"), new byte[] {8, 8});
        when(photos.findById("pht-1")).thenReturn(Optional.of(stored("aa", ITV + "/IMG.jpg")));
        when(interventions.requireVisible(eq(ITV), any(), any())).thenReturn(at(InterventionStatus.ON_SITE));

        InterventionPhotoService.Stored content = service().content("pht-1", auth("TECHNICIEN"), jwt());

        assertEquals("image/jpeg", content.contentType());
        assertArrayEquals(new byte[] {8, 8}, content.bytes());
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
