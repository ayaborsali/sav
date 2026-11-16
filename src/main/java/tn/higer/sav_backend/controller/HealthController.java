package tn.higer.sav_backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Sonde publique : la PWA vérifie que le serveur répond avant d'envoyer les
 * photos gardées. Aucune donnée métier ici, seulement la vie du service.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> sante() {
        return Map.of(
                "status", "ok",
                "time", OffsetDateTime.now().toString()
        );
    }
}
