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
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MissionGroupDtos.PhotoResponse;
import tn.higer.sav_backend.model.MissionGroupDossier;
import tn.higer.sav_backend.model.MissionGroupPhoto;
import tn.higer.sav_backend.model.MissionGroupPhotoAngle;
import tn.higer.sav_backend.repository.MissionGroupBusRepository;
import tn.higer.sav_backend.repository.MissionGroupDossierRepository;
import tn.higer.sav_backend.repository.MissionGroupPhotoRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les preuves photo d'un dossier groupe : deux angles, des formats de photo, une
 * empreinte calculée ici et une image qui ne se verse pas deux fois.
 */
@ExtendWith(MockitoExtension.class)
class MissionGroupPhotoServiceTest {

    @TempDir Path tmp;

    @Mock private MissionGroupPhotoRepository photos;
    @Mock private MissionGroupDossierRepository dossiers;
    @Mock private MissionGroupBusRepository buses;
    @Mock private PhotoExifService exif;
    @Mock private AuditService audit;

    private MissionGroupPhotoService service() {
        return new MissionGroupPhotoService(photos, dossiers, buses, exif, audit,
                tmp.toString(), 8_000_000L);
    }

    private static Jwt jeton() {
        return jeton("usr-1");
    }

    private static Jwt jeton(String subject) {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static Authentication chef() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CHEF_ATELIER")));
    }

    private static Authentication technicien(String subject) {
        return new UsernamePasswordAuthenticationToken(subject, null,
                List.of(new SimpleGrantedAuthority("ROLE_TECHNICIEN")));
    }

    private MissionGroupDossier dossier() {
        MissionGroupDossier d = new MissionGroupDossier();
        d.setId("mgd-1");
        d.setNumber("1740");
        d.setClientName("SRT Sousse");
        d.setTechnicianUserId("usr-1");
        return d;
    }

    @Test
    void une_photo_de_depart_est_ecrite_sur_le_disque_et_en_base() throws Exception {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        when(photos.existsByDossierIdAndSha256(eq("mgd-1"), anyString())).thenReturn(false);
        when(exif.read(any())).thenReturn(new PhotoExifService.Exif(null, null));
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "depart.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});

        PhotoResponse rendu = service().upload("mgd-1", fichier, "depart", null, null, jeton(), chef());

        ArgumentCaptor<MissionGroupPhoto> captor = ArgumentCaptor.forClass(MissionGroupPhoto.class);
        verify(photos).save(captor.capture());
        MissionGroupPhoto ecrite = captor.getValue();
        assertEquals(MissionGroupPhotoAngle.DEPART, ecrite.getAngle());
        assertEquals("la voiture au départ", rendu.angleLabel());
        assertTrue(Files.isRegularFile(tmp.resolve(ecrite.getFilePath())));
        assertEquals(64, ecrite.getSha256().length());
    }

    @Test
    void le_technicien_affecte_verse_sa_photo() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        when(photos.existsByDossierIdAndSha256(eq("mgd-1"), anyString())).thenReturn(false);
        when(exif.read(any())).thenReturn(new PhotoExifService.Exif(null, null));
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "depart.jpg", "image/jpeg", new byte[]{5, 6, 7});

        PhotoResponse rendu = service().upload("mgd-1", fichier, "depart", null, null,
                jeton(), technicien("usr-1"));

        assertEquals("depart", rendu.angle());
        verify(photos).save(any());
    }

    @Test
    void un_technicien_non_affecte_ne_verse_pas_de_photo() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "depart.jpg", "image/jpeg", new byte[]{8, 9});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().upload("mgd-1", fichier, "depart", null, null,
                        jeton("usr-2"), technicien("usr-2")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(photos, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void un_dossier_annule_ne_recoit_plus_de_photo() {
        MissionGroupDossier annule = dossier();
        annule.setCancelledAt(LocalDateTime.of(2026, 11, 10, 10, 0));
        annule.setCancelReason("Bus réparé sur place");
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(annule));
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "depart.jpg", "image/jpeg", new byte[]{3, 1, 4});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().upload("mgd-1", fichier, "depart", null, null, jeton(), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("annulé"), ex.getReason());
        verify(photos, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void un_technicien_non_affecte_ne_lit_pas_les_photos() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().list("mgd-1", jeton("usr-2"), technicien("usr-2")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void un_fichier_qui_n_est_pas_une_image_est_refuse() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "note.txt", "text/plain", new byte[]{1});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().upload("mgd-1", fichier, "depart", null, null, jeton(), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Format refusé"));
    }

    @Test
    void un_angle_inconnu_est_refuse_avant_de_lire_le_fichier() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().upload("mgd-1", fichier, "autre", null, null, jeton(), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Angle de photo inconnu"));
    }

    @Test
    void la_meme_image_ne_se_verse_pas_deux_fois_sur_le_meme_dossier() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        when(photos.existsByDossierIdAndSha256(eq("mgd-1"), anyString())).thenReturn(true);
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{9, 9, 9});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().upload("mgd-1", fichier, "depart", null, null, jeton(), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("déjà versé"));
    }
}
