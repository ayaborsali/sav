package tn.higer.sav_backend.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.higer.sav_backend.dto.MissionGroupDtos.AttachmentResponse;
import tn.higer.sav_backend.service.MissionGroupAttachmentService;
import tn.higer.sav_backend.service.MissionGroupChecklistImportService;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * La fiche atelier jointe à un dossier groupe.
 *
 * L'envoi est en multipart : la fiche ne transite pas en base64 dans le JSON.
 * La relecture passe par /content et demande le jeton, comme pour les photos.
 */
@RestController
@RequestMapping("/api")
public class MissionGroupAttachmentController {

    private final MissionGroupAttachmentService service;
    private final MissionGroupChecklistImportService importer;

    public MissionGroupAttachmentController(MissionGroupAttachmentService service,
                                            MissionGroupChecklistImportService importer) {
        this.service = service;
        this.importer = importer;
    }

    @PostMapping("/missions-groupe/fiche-atelier/apercu")
    public MissionGroupChecklistImportService.Preview preview(@RequestParam("file") MultipartFile file,
                                                              Authentication auth) {
        return importer.preview(file, auth);
    }

    @GetMapping("/missions-groupe/{dossierId}/pieces-jointes")
    public List<AttachmentResponse> list(@PathVariable String dossierId,
                                         @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.list(dossierId, jwt, auth);
    }

    /** Jointer la fiche : un geste de l'atelier, qui détient le papier d'origine. */
    @PostMapping("/missions-groupe/{dossierId}/pieces-jointes")
    public AttachmentResponse upload(@PathVariable String dossierId,
                                     @RequestParam("file") MultipartFile file,
                                     @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.upload(dossierId, file, jwt, auth);
    }

    @GetMapping("/mission-group-attachments/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id,
                                          @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        MissionGroupAttachmentService.Stored stored = service.content(id, jwt, auth);
        MediaType type = stored.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(stored.contentType());
        boolean excel = "application/vnd.ms-excel".equalsIgnoreCase(stored.contentType())
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equalsIgnoreCase(stored.contentType());
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (excel ? ContentDisposition.attachment() : ContentDisposition.inline())
                                .filename(stored.fileName(), StandardCharsets.UTF_8).build().toString())
                // Une pièce lue aujourd'hui n'est pas mise en cache : l'écran montre l'état du jour.
                .cacheControl(CacheControl.noStore())
                .body(stored.bytes());
    }
}
