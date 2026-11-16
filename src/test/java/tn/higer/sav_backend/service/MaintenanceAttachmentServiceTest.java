package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenanceDtos.AttachmentResponse;
import tn.higer.sav_backend.model.MaintenanceAttachment;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Les pièces jointes d'un document : le BL signé scanné, la liste de prix du client.
 * Elles portent la preuve de ce qui a été accepté, donc les règles dures sont testées
 * ici et non sur l'écran :
 *
 * 1. le fichier est sur le disque, la base ne garde que le chemin — un PDF encodé en
 *    base alourdirait chaque liste de BL et ne se relirait pas ;
 * 2. l'empreinte et le type viennent des OCTETS reçus : le navigateur ne les fournit
 *    pas, donc un fichier renommé en « .pdf » ne devient pas une preuve ;
 * 3. le chemin lu dans la base ne sort pas du dossier de stockage.
 *
 * Ce qui est refusé doit être refusé AVANT écriture : un écran qui affiche « fichier
 * trop lourd » après avoir rempli le disque n'a rien réparé.
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceAttachmentServiceTest {

    @Mock private MaintenanceRepository documents;
    @Mock private MaintenanceAttachmentRepository attachments;
    @Mock private AuditService audit;

    @TempDir Path storage;

    private static final String DOC = "maint-1";

    // ------------------------------------------------------------------ fixtures

    private MaintenanceAttachmentService service() {
        return service(10_000_000L);
    }

    private MaintenanceAttachmentService service(long maxBytes) {
        return new MaintenanceAttachmentService(documents, attachments, audit,
                storage.toString(), maxBytes);
    }

    private static MaintenanceItem document() {
        MaintenanceItem m = new MaintenanceItem();
        m.setId(DOC);
        m.setNumber("BL-2026-0100");
        m.setType(MaintenanceType.BL);
        m.setStatus(MaintenanceStatus.DRAFT);
        return m;
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).claim("email", "sav@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Un vrai entête PDF : c'est lui qui décide, pas le nom ni le Content-Type. */
    private static byte[] pdf() {
        return new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A};
    }

    private static byte[] jpeg() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private static MultipartFile fichier(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    private static MaintenanceAttachment piece(String id, String itemId, String relativePath) {
        MaintenanceAttachment a = new MaintenanceAttachment();
        a.setId(id);
        a.setMaintenanceItemId(itemId);
        a.setOriginalName("BL-signe.pdf");
        a.setContentType("application/pdf");
        a.setSizeBytes(9L);
        a.setStoragePath(relativePath);
        a.setSha256("aa");
        a.setUploadedAt(LocalDateTime.now());
        return a;
    }

    // ------------------------------------------------------------------ versement

    @Test
    void leServeurCalculeLEmpreinteEtEcritLaPieceSurLeDisque() throws Exception {
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));
        when(attachments.existsByMaintenanceItemIdAndSha256(DOC, sha256(pdf()))).thenReturn(false);

        AttachmentResponse response = service().upload(DOC, fichier("BL-signe.pdf", "application/pdf", pdf()), jwt());

        ArgumentCaptor<MaintenanceAttachment> saved = ArgumentCaptor.forClass(MaintenanceAttachment.class);
        verify(attachments).save(saved.capture());
        MaintenanceAttachment a = saved.getValue();
        assertEquals(64, a.getSha256().length(), "l'empreinte n'est pas un SHA-256 complet");
        assertEquals(DOC, a.getMaintenanceItemId());
        assertEquals("application/pdf", a.getContentType());
        assertEquals(pdf().length, a.getSizeBytes().longValue());
        assertEquals("sav@saphir.tn", a.getUploadedBy());
        assertNotNull(a.getUploadedAt());
        // Le nom affiché est nettoyé : il ne peut pas transporter de chemin.
        assertEquals("BL-signe.pdf", a.getOriginalName());
        // Le chemin est relatif à la racine de stockage : c'est le serveur qui l'a écrit.
        assertTrue(a.getStoragePath().startsWith(DOC + "/"), a.getStoragePath());
        assertTrue(a.getStoragePath().endsWith(".pdf"), a.getStoragePath());

        Path ecrit = storage.resolve("attachments").resolve(a.getStoragePath());
        assertTrue(Files.isRegularFile(ecrit), "la pièce n'est pas sur le disque : " + ecrit);
        assertArrayEquals(pdf(), Files.readAllBytes(ecrit));

        // L'URL est rendue sans le préfixe /api, comme celle des photos de sortie.
        assertEquals("/maintenance/" + DOC + "/attachments/" + a.getId() + "/content", response.url());
    }

    @Test
    void lesTroisNaturesAccepteesSontReconnuesSurLeursOctets() throws Exception {
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));
        when(attachments.existsByMaintenanceItemIdAndSha256(any(), any())).thenReturn(false);

        assertEquals("application/pdf", service().upload(DOC, fichier("a.pdf", null, pdf()), jwt()).contentType());
        assertEquals("image/jpeg", service().upload(DOC, fichier("b.jpg", null, jpeg()), jwt()).contentType());
        assertEquals("image/png", service().upload(DOC, fichier("c.png", null, png()), jwt()).contentType());
    }

    @Test
    void unFichierQuiNestNiUnePhotoNiUnPdfEstRefuse() {
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));

        // Un tableur renommé en « .pdf » : l'extension et le Content-Type mentent,
        // les octets non. C'est sur eux que la décision se prend.
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service()
                .upload(DOC, fichier("prix.pdf", "application/pdf", new byte[] {1, 2, 3, 4}), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("ni une photo JPEG"), e.getReason());
        verifyNoInteractions(attachments);
    }

    @Test
    void unFichierQuiAnnonceUnAutreTypeEstRefuse() {
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service()
                .upload(DOC, fichier("scan.jpg", "image/png", jpeg()), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        // Les deux types sont nommés : l'atelier comprend ce que l'écran a envoyé.
        assertTrue(e.getReason().contains("image/png"), e.getReason());
        assertTrue(e.getReason().contains("image/jpeg"), e.getReason());
        verifyNoInteractions(attachments);
    }

    @Test
    void aucunFichierRecuNeSeJointPas() {
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service()
                .upload(DOC, fichier("vide.pdf", "application/pdf", new byte[0]), jwt()));

        assertTrue(e.getReason().contains("Aucun fichier reçu"), e.getReason());
        verifyNoInteractions(attachments);
    }

    @Test
    void unePieceTropLourdeEstRefuseeAvantEcriture() {
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));

        byte[] lourd = new byte[5_000];
        System.arraycopy(pdf(), 0, lourd, 0, pdf().length);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(1_000L).upload(DOC, fichier("scan.pdf", "application/pdf", lourd), jwt()));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatusCode());
        assertTrue(e.getReason().contains("Trop lourd") || e.getReason().contains("trop lourd"), e.getReason());
        verifyNoInteractions(attachments);
    }

    @Test
    void laMemePieceJointeDeuxFoisNeComptePasDeuxPreuves() {
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));
        when(attachments.existsByMaintenanceItemIdAndSha256(DOC, sha256(pdf()))).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service()
                .upload(DOC, fichier("BL.pdf", "application/pdf", pdf()), jwt()));

        assertTrue(e.getReason().contains("déjà joint"), e.getReason());
        verify(attachments, never()).save(any());
    }

    @Test
    void unDocumentInconnuNaccepteAucunePiece() {
        when(documents.findById("maint-x")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service()
                .upload("maint-x", fichier("BL.pdf", "application/pdf", pdf()), jwt()));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertTrue(e.getReason().contains("introuvable"), e.getReason());
        verifyNoInteractions(attachments);
    }

    @Test
    void leNomDuFichierEstNettoyeAvantECriture() {
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));
        when(attachments.existsByMaintenanceItemIdAndSha256(any(), any())).thenReturn(false);

        service().upload(DOC, fichier("../../windows/system.ini", "image/jpeg", jpeg()), jwt());

        ArgumentCaptor<MaintenanceAttachment> saved = ArgumentCaptor.forClass(MaintenanceAttachment.class);
        verify(attachments).save(saved.capture());
        String name = saved.getValue().getOriginalName();
        assertFalse(name.contains(".."), name);
        assertFalse(name.contains("/") || name.contains("\\"), name);
        assertEquals("system.ini", name);
    }

    // ---------------------------------------------------------------------- lecture

    @Test
    void laPieceSeRelitDepuisLeDisque() throws Exception {
        Path dir = storage.resolve("attachments").resolve(DOC);
        Files.createDirectories(dir);
        Files.write(dir.resolve("abc-BL.pdf"), pdf());
        when(attachments.findById("att-1")).thenReturn(Optional.of(piece("att-1", DOC, DOC + "/abc-BL.pdf")));

        MaintenanceAttachmentService.Content content = service().content("att-1");

        assertArrayEquals(pdf(), content.bytes());
        assertEquals("application/pdf", content.contentType());
        assertEquals("BL-signe.pdf", content.fileName());
    }

    @Test
    void unCheminHorsDossierDeStockageNeLitPasLeDisque() {
        // La valeur vient de la base : elle est relue sous contrôle, sinon une ligne
        // trafiquée ferait lire un fichier du serveur à un utilisateur connecté.
        when(attachments.findById("att-1")).thenReturn(Optional.of(piece("att-1", DOC, "../../windows/win.ini")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service().content("att-1"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertTrue(e.getReason().contains("absent du disque"), e.getReason());
    }

    @Test
    void unePieceInconnueRendUneErreurClaire() {
        when(attachments.findById("att-x")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service().content("att-x"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertTrue(e.getReason().contains("introuvable"), e.getReason());
    }

    @Test
    void laListeNeMontreQueCeQuiEstJointAuDocument() {
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));
        when(attachments.findByMaintenanceItemIdOrderByUploadedAtAsc(DOC))
                .thenReturn(List.of(piece("att-1", DOC, DOC + "/a.pdf"), piece("att-2", DOC, DOC + "/b.pdf")));

        List<AttachmentResponse> files = service().list(DOC);

        assertEquals(2, files.size());
        assertEquals(List.of("att-1", "att-2"), files.stream().map(AttachmentResponse::id).toList());
        files.forEach(f -> assertEquals("/maintenance/" + DOC + "/attachments/" + f.id() + "/content", f.url()));
    }

    // ------------------------------------------------------------------- suppression

    @Test
    void supprimerUnePieceEffaceAussiLeFichierSurLeDisque() throws Exception {
        Path dir = storage.resolve("attachments").resolve(DOC);
        Files.createDirectories(dir);
        Path fichier = dir.resolve("abc-BL.pdf");
        Files.write(fichier, pdf());
        MaintenanceAttachment a = piece("att-1", DOC, DOC + "/abc-BL.pdf");
        when(attachments.findById("att-1")).thenReturn(Optional.of(a));
        when(documents.findById(DOC)).thenReturn(Optional.of(document()));

        service().delete(DOC, "att-1", jwt());

        verify(attachments).delete(a);
        assertFalse(Files.exists(fichier), "la ligne est partie de la base, le fichier est resté");
        verify(audit).record(eq("DELETE"), eq("MAINTENANCE_ATTACHMENT"), eq("att-1"),
                contains("BL-2026-0100"), any());
    }

    @Test
    void unePieceQuiNestPasDeCeDocumentNestPasEffacee() {
        // L'URL porte deux identifiants : ils doivent correspondre, sinon une adresse
        // recopiée d'un autre écran effacerait une preuve sans rapport.
        when(attachments.findById("att-1")).thenReturn(Optional.of(piece("att-1", "maint-9", "maint-9/a.pdf")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().delete(DOC, "att-1", jwt()));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(attachments, never()).delete(any(MaintenanceAttachment.class));
    }

    @Test
    void laSuppressionDuDocumentEmporteSesPieces() throws Exception {
        Path dir = storage.resolve("attachments").resolve(DOC);
        Files.createDirectories(dir);
        Files.write(dir.resolve("a.pdf"), pdf());
        Files.write(dir.resolve("b.jpg"), jpeg());
        when(attachments.findByMaintenanceItemIdOrderByUploadedAtAsc(DOC))
                .thenReturn(List.of(piece("att-1", DOC, DOC + "/a.pdf"), piece("att-2", DOC, DOC + "/b.jpg")));

        int otees = service().supprimerAvecDocument(DOC);

        assertEquals(2, otees);
        verify(attachments).deleteByMaintenanceItemId(DOC);
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count(), "des fichiers sont restés orphelins du document");
        }
    }

    /** Un document sans pièce se supprime sans rien casser : le compte rendu reste juste. */
    @Test
    void unDocumentSansPieceNEffaceRien() {
        when(attachments.findByMaintenanceItemIdOrderByUploadedAtAsc(DOC)).thenReturn(List.of());

        assertEquals(0, service().supprimerAvecDocument(DOC));

        verify(attachments).deleteByMaintenanceItemId(DOC);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
