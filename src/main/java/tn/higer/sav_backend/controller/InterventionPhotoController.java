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
import tn.higer.sav_backend.dto.PhotoDtos.PhotoResponse;
import tn.higer.sav_backend.dto.PhotoDtos.ProofStatusDto;
import tn.higer.sav_backend.service.InterventionPhotoService;

import java.util.List;

/**
 * Preuves photo d'une sortie.
 *
 * L'envoi est en multipart, pas en base64 dans un champ JSON : une image encodée
 * grossit d'un tiers et transite par la couche JSON, ce qui ne prouverait rien de
 * plus sur sa nature. Le serveur garde les octets tels qu'ils sont arrivés, c'est
 * sur eux que l'empreinte est calculée.
 *
 * La relecture d'une image passe par /content et demande le jeton : le rôle
 * CONSULTATION, qui n'entre pas dans le module, ne peut pas charger les clichés
 * même s'il connaît l'identifiant.
 */
@RestController
@RequestMapping("/api")
public class InterventionPhotoController {

    private final InterventionPhotoService service;

    public InterventionPhotoController(InterventionPhotoService service) {
        this.service = service;
    }

    @GetMapping("/interventions/{interventionId}/photos")
    public List<PhotoResponse> list(@PathVariable String interventionId,
                                    @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.list(interventionId, auth, jwt);
    }

    /** Ce qui manque comme preuve avant de clôturer : signal, pas interdiction muette. */
    @GetMapping("/interventions/{interventionId}/photos/proof")
    public ProofStatusDto proof(@PathVariable String interventionId,
                                @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        service.list(interventionId, auth, jwt); // même contrôle de visibilité que la liste
        return service.proof(interventionId);
    }

    @PostMapping("/interventions/{interventionId}/photos")
    public PhotoResponse upload(@PathVariable String interventionId,
                                @RequestParam("file") MultipartFile file,
                                @RequestParam("angle") String angle,
                                @RequestParam(value = "note", required = false) String note,
                                @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.upload(interventionId, file, angle, note, jwt, auth);
    }

    @GetMapping("/intervention-photos/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id,
                                          @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        InterventionPhotoService.Stored stored = service.content(id, auth, jwt);
        MediaType type = stored.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(stored.contentType());
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + stored.fileName() + "\"")
                // Une preuve ne se met pas en cache : le fichier peut être remplacé
                // par un administrateur, et l'écran doit montrer l'état du jour.
                .cacheControl(CacheControl.noStore())
                .body(stored.bytes());
    }
}
