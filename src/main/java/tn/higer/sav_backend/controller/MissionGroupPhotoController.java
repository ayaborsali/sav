package tn.higer.sav_backend.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.higer.sav_backend.dto.MissionGroupDtos.PhotoResponse;
import tn.higer.sav_backend.service.MissionGroupPhotoService;

import java.util.List;

/**
 * Les deux preuves photo d'un dossier groupe.
 *
 * L'envoi est en multipart : l'image ne transite pas en base64 dans le JSON. La
 * relecture passe par /content et demande le jeton, comme pour les sorties.
 */
@RestController
@RequestMapping("/api")
public class MissionGroupPhotoController {

    private final MissionGroupPhotoService service;

    public MissionGroupPhotoController(MissionGroupPhotoService service) {
        this.service = service;
    }

    @GetMapping("/missions-groupe/{dossierId}/photos")
    public List<PhotoResponse> list(@PathVariable String dossierId,
                                    @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.list(dossierId, jwt, auth);
    }

    /**
     * Verser un cliché. L'angle dit ce qu'il montre ; pour le bus avant / après,
     * {@code busId} dit de quel bus il s'agit.
     */
    @PostMapping("/missions-groupe/{dossierId}/photos")
    public PhotoResponse upload(@PathVariable String dossierId,
                                @RequestParam("file") MultipartFile file,
                                @RequestParam("angle") String angle,
                                @RequestParam(value = "busId", required = false) String busId,
                                @RequestParam(value = "note", required = false) String note,
                                @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.upload(dossierId, file, angle, busId, note, jwt, auth);
    }

    @GetMapping("/mission-group-photos/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id,
                                          @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        MissionGroupPhotoService.Stored stored = service.content(id, jwt, auth);
        MediaType type = stored.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(stored.contentType());
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + stored.fileName() + "\"")
                // Une preuve ne se met pas en cache : l'écran doit montrer l'état du jour.
                .cacheControl(CacheControl.noStore())
                .body(stored.bytes());
    }
}
