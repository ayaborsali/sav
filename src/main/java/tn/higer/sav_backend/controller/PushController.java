package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tn.higer.sav_backend.dto.PushDtos.PublicKeyResponse;
import tn.higer.sav_backend.dto.PushDtos.SubscriptionRequest;
import tn.higer.sav_backend.dto.PushDtos.UnsubscribeRequest;
import tn.higer.sav_backend.service.PushNotificationService;

/**
 * Ce que le téléphone appelle pour accepter — ou retirer — les notifications.
 *
 * Chaque compte n'abonne que ses propres téléphones : le propriétaire de
 * l'adresse est toujours le compte du jeton, jamais un identifiant venu du
 * corps de la requête.
 */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushNotificationService push;

    public PushController(PushNotificationService push) {
        this.push = push;
    }

    /** La clé que le navigateur lit avant de s'abonner (et qu'il garde ensuite). */
    @GetMapping("/public-key")
    public PublicKeyResponse publicKey() {
        return new PublicKeyResponse(push.publicKey());
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abonner(@Valid @RequestBody SubscriptionRequest request,
                        @AuthenticationPrincipal Jwt jwt) {
        push.subscribe(jwt == null ? null : jwt.getSubject(), request);
    }

    @DeleteMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desabonner(@Valid @RequestBody UnsubscribeRequest request,
                           @AuthenticationPrincipal Jwt jwt) {
        push.unsubscribe(jwt == null ? null : jwt.getSubject(), request.endpoint());
    }
}
