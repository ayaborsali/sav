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
import tn.higer.sav_backend.dto.MissionGroupDtos.AttachmentResponse;
import tn.higer.sav_backend.model.MissionGroupAttachment;
import tn.higer.sav_backend.model.MissionGroupDossier;
import tn.higer.sav_backend.repository.MissionGroupAttachmentRepository;
import tn.higer.sav_backend.repository.MissionGroupDossierRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La fiche atelier jointe au dossier : un geste de l'atelier, en PDF ou en
 * photo, une empreinte calculée ici, et une fiche qui ne se verse pas deux fois.
 */
@ExtendWith(MockitoExtension.class)
class MissionGroupAttachmentServiceTest {

    @TempDir Path tmp;

    @Mock private MissionGroupAttachmentRepository attachments;
    @Mock private MissionGroupDossierRepository dossiers;
    @Mock private AuditService audit;

    private MissionGroupAttachmentService service() {
        return new MissionGroupAttachmentService(attachments, dossiers, audit,
                tmp.toString(), 10_000_000L);
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

    private static Authentication technicien() {
        return new UsernamePasswordAuthenticationToken("usr-1", null,
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
    void un_pdf_est_ecrit_sur_le_disque_et_en_base() throws Exception {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        when(attachments.existsByDossierIdAndSha256(eq("mgd-1"), anyString())).thenReturn(false);
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "fiche.pdf", "application/pdf", new byte[]{1, 2, 3, 4});

        AttachmentResponse rendu = service().upload("mgd-1", fichier, jeton(), chef());

        ArgumentCaptor<MissionGroupAttachment> captor = ArgumentCaptor.forClass(MissionGroupAttachment.class);
        verify(attachments).save(captor.capture());
        MissionGroupAttachment ecrite = captor.getValue();
        assertTrue(ecrite.getFilePath().endsWith(".pdf"));
        assertTrue(Files.isRegularFile(tmp.resolve(ecrite.getFilePath())));
        assertEquals(64, ecrite.getSha256().length());
        assertEquals("/mission-group-attachments/" + ecrite.getId() + "/content", rendu.url());
    }

    @Test
    void le_nom_du_fichier_ne_depend_pas_des_chemins_windows() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "C:\\fakepath\\fiche été?.pdf", "application/pdf", new byte[]{1});
        AttachmentResponse rendu = service().upload("mgd-1", fichier, jeton(), chef());
        assertEquals("fiche été?.pdf", rendu.originalName());
    }

    @Test
    void le_technicien_ne_joint_pas_la_fiche() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "fiche.pdf", "application/pdf", new byte[]{1});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().upload("mgd-1", fichier, jeton(), technicien()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(attachments, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void un_fichier_qui_n_est_pas_un_pdf_ni_une_photo_est_refuse() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "fiche.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().upload("mgd-1", fichier, jeton(), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Format refusé"));
    }

    @Test
    void la_meme_fiche_ne_se_verse_pas_deux_fois_sur_le_meme_dossier() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        when(attachments.existsByDossierIdAndSha256(eq("mgd-1"), anyString())).thenReturn(true);
        MockMultipartFile fichier = new MockMultipartFile(
                "file", "fiche.pdf", "application/pdf", new byte[]{9, 9});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().upload("mgd-1", fichier, jeton(), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("déjà versée"));
    }

    @Test
    void un_fichier_vide_est_refuse() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().upload("mgd-1", new MockMultipartFile("file", new byte[0]), jeton(), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void une_fiche_trop_lourde_est_refusee() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));
        MissionGroupAttachmentService limite = new MissionGroupAttachmentService(
                attachments, dossiers, audit, tmp.toString(), 3L);
        MockMultipartFile fichier = new MockMultipartFile("file", "fiche.pdf", "application/pdf", new byte[4]);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> limite.upload("mgd-1", fichier, jeton(), chef()));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.getStatusCode());
    }

    @Test
    void le_technicien_affecte_relit_le_fichier_enregistre() throws Exception {
        MissionGroupAttachment piece = new MissionGroupAttachment();
        piece.setId("mga-1");
        piece.setDossierId("mgd-1");
        piece.setFilePath("fiche.pdf");
        piece.setContentType("application/pdf");
        piece.setOriginalName("fiche.pdf");
        Files.write(tmp.resolve("fiche.pdf"), new byte[]{1, 2, 3});
        when(attachments.findById("mga-1")).thenReturn(Optional.of(piece));
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));

        MissionGroupAttachmentService.Stored lu = service().content("mga-1", jeton(), technicien());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1, 2, 3}, lu.bytes());
        assertEquals("application/pdf", lu.contentType());
        assertEquals("fiche.pdf", lu.fileName());
    }

    @Test
    void un_technicien_non_affecte_ne_lit_pas_la_fiche() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().list("mgd-1", jeton("usr-2"),
                        new UsernamePasswordAuthenticationToken("usr-2", null,
                                List.of(new SimpleGrantedAuthority("ROLE_TECHNICIEN")))));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
