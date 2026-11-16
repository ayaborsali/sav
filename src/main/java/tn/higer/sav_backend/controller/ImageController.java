package tn.higer.sav_backend.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.model.ImageEntity;
import tn.higer.sav_backend.repository.ImageRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Les photos que la PWA garde hors ligne puis verse au retour du réseau.
 *
 * L'identifiant est fourni par le téléphone : un second envoi du même
 * identifiant répond 200 avec la ligne existante, sans réécrire l'image. C'est
 * ce qui rend le déclencheur automatique sûr — renvoyer n'est jamais dupliquer.
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private static final long TAILLE_MAX = 8_000_000L;

    private final ImageRepository images;

    public ImageController(ImageRepository images) {
        this.images = images;
    }

    public record ImageResponse(
            String id,
            String fileName,
            String contentType,
            long sizeBytes,
            LocalDateTime createdAt,
            LocalDateTime uploadedAt,
            String url
    ) {
    }

    @PostMapping("/upload")
    public ResponseEntity<ImageResponse> upload(@RequestParam("id") String id,
                                                @RequestParam(value = "createdAt", required = false) String createdAt,
                                                @RequestParam("file") MultipartFile file,
                                                @AuthenticationPrincipal Jwt jwt) {
        if (id == null || id.isBlank() || id.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifiant de photo invalide.");
        }
        Optional<ImageEntity> deja = images.findById(id);
        if (deja.isPresent()) {
            return ResponseEntity.ok(reponse(deja.get()));
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier est vide.");
        }
        if (file.getSize() > TAILLE_MAX) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Photo trop lourde (" + file.getSize() + " octets, limite " + TAILLE_MAX + ").");
        }
        String type = file.getContentType();
        if (type == null || !type.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Seules les images sont acceptées.");
        }

        byte[] octets;
        try {
            octets = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le fichier n'a pas pu être lu pendant l'envoi. Réessayez.");
        }

        ImageEntity image = new ImageEntity();
        image.setId(id);
        image.setFileName(nomFichier(file.getOriginalFilename()));
        image.setContentType(type);
        image.setSizeBytes(octets.length);
        image.setClientCreatedAt(heureClient(createdAt));
        image.setUploadedAt(LocalDateTime.now());
        image.setUploadedBy(jwt != null ? jwt.getSubject() : null);
        image.setData(octets);

        try {
            images.saveAndFlush(image);
        } catch (org.springframework.dao.DataIntegrityViolationException doublon) {
            // Deux envois simultanés du même identifiant : le second lit la ligne
            // écrite par le premier et répond 200, comme un renvoi ordinaire.
            return images.findById(id)
                    .map(existante -> ResponseEntity.ok(reponse(existante)))
                    .orElseThrow(() -> doublon);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(reponse(image));
    }

    @GetMapping
    public List<ImageResponse> list() {
        return images.findAllByOrderByUploadedAtDesc().stream().map(this::reponse).toList();
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id) {
        ImageEntity image = images.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cette photo n'existe pas."));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + image.getFileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(image.getData());
    }

    private ImageResponse reponse(ImageEntity image) {
        return new ImageResponse(
                image.getId(),
                image.getFileName(),
                image.getContentType(),
                image.getSizeBytes(),
                image.getClientCreatedAt(),
                image.getUploadedAt(),
                "/api/images/" + image.getId() + "/file"
        );
    }

    private static String nomFichier(String original) {
        if (original == null || original.isBlank()) return "photo.jpg";
        String propre = original.replaceAll("[\\\\/]", "_");
        return propre.length() > 255 ? propre.substring(propre.length() - 255) : propre;
    }

    /** L'heure de l'appareil, facultative : le serveur garde de toute façon la sienne. */
    private static LocalDateTime heureClient(String texte) {
        if (texte == null || texte.isBlank()) return null;
        try {
            return OffsetDateTime.parse(texte).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(texte);
            } catch (DateTimeParseException encore) {
                return null;
            }
        }
    }
}
