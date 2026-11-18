package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Les trois échanges du téléphone avec la poussée.
 *
 * La clé publique part vers le navigateur ; l'abonnement revient du navigateur
 * avec son adresse et ses deux clés ; le désabonnement ne renvoie que l'adresse.
 */
public final class PushDtos {

    private PushDtos() {
    }

    /** La clé VAPID publique : le navigateur s'en sert pour s'abonner. */
    public record PublicKeyResponse(String publicKey) {
    }

    public record SubscriptionRequest(@NotBlank @Size(max = 2000) String endpoint,
                                      @NotBlank @Size(max = 255) String p256dh,
                                      @NotBlank @Size(max = 255) String auth,
                                      @Size(max = 255) String userAgent) {
    }

    public record UnsubscribeRequest(@NotBlank @Size(max = 2000) String endpoint) {
    }
}
