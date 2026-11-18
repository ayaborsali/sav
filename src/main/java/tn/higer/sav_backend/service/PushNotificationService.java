package tn.higer.sav_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Utils;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.PushDtos.SubscriptionRequest;
import tn.higer.sav_backend.model.PushSubscription;
import tn.higer.sav_backend.model.PushVapidKey;
import tn.higer.sav_backend.repository.PushSubscriptionRepository;
import tn.higer.sav_backend.repository.PushVapidKeyRepository;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Faire sonner le téléphone : les notifications qui arrivent quand l'application
 * est fermée.
 *
 * Un navigateur ne reçoit rien sans deux accords : le téléphone s'abonne auprès
 * de son service de poussée (Chrome, Safari), et le serveur signe chaque envoi
 * avec la paire VAPID que le navigateur a enregistrée à cet abonnement. C'est
 * cette paire qui est gardée en base — la changer rendrait muets tous les
 * téléphones déjà abonnés.
 *
 * Le message suit le format que lit le service worker d'Angular : une
 * notification, et sous {@code data.onActionClick.default}, l'écran à ouvrir
 * quand on la touche. Rien d'autre n'est interprété côté téléphone.
 *
 * Un envoi ne doit jamais retarder un enregistrement : les appels qui partent
 * d'une écriture (dossier affecté, alerte du dépannage) passent par
 * {@link #annoncer} sur le pool dédié. Le scan du départ, lui, appelle
 * {@link #deliver} et attend le résultat : c'est ce résultat qui décide si
 * l'annonce est notée comme faite.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);
    private static final String VAPID_ID = "vapid";

    private final PushSubscriptionRepository subscriptions;
    private final PushVapidKeyRepository keys;
    private final String subject;
    private final ObjectMapper json = new ObjectMapper();

    private PushService pushService;
    private String publicKey;

    public PushNotificationService(PushSubscriptionRepository subscriptions,
                                   PushVapidKeyRepository keys,
                                   @Value("${app.push.subject:https://sav-2dgg.onrender.com}") String subject) {
        this.subscriptions = subscriptions;
        this.keys = keys;
        this.subject = subject;
    }

    /** Le texte d'une notification : ce que le téléphone affiche et l'écran qu'il ouvre. */
    public record Message(String title, String body, String url, String tag) {
    }

    // ------------------------------------------------------------- la paire VAPID

    /** La clé publique que le navigateur lit avant de s'abonner. */
    public String publicKey() {
        ensureKeyPair();
        return publicKey;
    }

    /**
     * Charge la paire, ou la fabrique une première fois. La ligne est écrite avant
     * le premier envoi : deux instances ne doivent pas se réveiller avec deux
     * paires différentes, sinon l'une des deux ne signe plus rien.
     */
    private synchronized void ensureKeyPair() {
        if (pushService != null) {
            return;
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        PushVapidKey row = keys.findById(VAPID_ID).orElseGet(this::generateKeyPair);
        try {
            pushService = new PushService(row.getPublicKey(), row.getPrivateKey(), subject);
            publicKey = row.getPublicKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "La paire VAPID enregistrée ne se relit pas : la poussée reste éteinte.", e);
        }
    }

    private PushVapidKey generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", "BC");
            generator.initialize(new ECGenParameterSpec("prime256v1"), new SecureRandom());
            KeyPair pair = generator.generateKeyPair();

            PushVapidKey row = new PushVapidKey();
            row.setId(VAPID_ID);
            row.setPublicKey(base64url(Utils.encode((ECPublicKey) pair.getPublic())));
            row.setPrivateKey(base64url(Utils.encode((ECPrivateKey) pair.getPrivate())));
            row.setCreatedAt(LocalDateTime.now());
            return keys.save(row);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Impossible de fabriquer la paire VAPID : la poussée reste éteinte.", e);
        }
    }

    private static String base64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ------------------------------------------------------------- l'abonnement

    /**
     * Le téléphone s'abonne (ou se réabonne). L'adresse est unique : si ce même
     * téléphone était sur un autre compte, la ligne change de propriétaire — sans
     * quoi l'ancien compte continuerait de le faire sonner.
     */
    public void subscribe(String userId, SubscriptionRequest request) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connectez-vous pour abonner ce téléphone.");
        }
        LocalDateTime now = LocalDateTime.now();
        String endpoint = request.endpoint().trim();
        PushSubscription row = subscriptions.findByEndpoint(endpoint).orElseGet(() -> {
            PushSubscription fresh = new PushSubscription();
            fresh.setId("psb-" + UUID.randomUUID());
            fresh.setEndpoint(endpoint);
            fresh.setCreatedAt(now);
            return fresh;
        });
        row.setUserId(userId);
        row.setP256dh(request.p256dh().trim());
        row.setAuth(request.auth().trim());
        row.setUserAgent(truncate(request.userAgent(), 255));
        row.setLastSeenAt(now);
        subscriptions.save(row);
    }

    /** Le téléphone se retire. Chacun ne retire que ses propres adresses. */
    public void unsubscribe(String userId, String endpoint) {
        if (userId == null || endpoint == null) {
            return;
        }
        subscriptions.findByEndpoint(endpoint.trim())
                .filter(row -> userId.equals(row.getUserId()))
                .ifPresent(subscriptions::delete);
    }

    // ------------------------------------------------------------- l'envoi

    /** Depuis une écriture : ne fait pas attendre l'enregistrement. */
    @Async("pushExecutor")
    public void annoncer(String userId, Message message) {
        deliver(userId, message);
    }

    /**
     * Envoie à tous les téléphones d'un compte.
     *
     * @return true si au moins un téléphone a accepté la notification (2xx) —
     *         c'est ce que la règle du départ note pour ne pas resonner
     */
    public boolean deliver(String userId, Message message) {
        if (userId == null || message == null) {
            return false;
        }
        List<PushSubscription> abonnes = subscriptions.findByUserId(userId);
        if (abonnes.isEmpty()) {
            return false;
        }
        ensureKeyPair();
        String payload = payload(message);
        boolean delivered = false;
        for (PushSubscription abonne : abonnes) {
            try {
                Notification notification = new Notification(
                        abonne.getEndpoint(), abonne.getP256dh(), abonne.getAuth(), payload);
                HttpResponse response = pushService.send(notification);
                int status = response.getStatusLine().getStatusCode();
                if (status >= 200 && status < 300) {
                    delivered = true;
                } else if (status == 404 || status == 410) {
                    // Le navigateur a jeté l'abonnement : cette ligne ne sonnera plus.
                    subscriptions.delete(abonne);
                } else {
                    log.warn("Poussée refusée par le service du téléphone : statut {}", status);
                }
            } catch (Exception e) {
                log.warn("Poussée impossible vers un téléphone : {}", e.getMessage());
            }
        }
        return delivered;
    }

    /**
     * Le format attendu par le service worker d'Angular : la notification à
     * afficher, et l'écran à ouvrir d'un toucher.
     */
    private String payload(Message message) {
        ObjectNode root = json.createObjectNode();
        ObjectNode notification = root.putObject("notification");
        notification.put("title", message.title());
        notification.put("body", message.body());
        notification.put("lang", "fr");
        notification.put("icon", "/icons/icon-192.png");
        notification.put("badge", "/icons/icon-192.png");
        notification.put("tag", message.tag());
        ObjectNode defaultAction = notification.putObject("data")
                .putObject("onActionClick")
                .putObject("default");
        defaultAction.put("operation", "navigateLastFocusedOrOpen");
        defaultAction.put("url", message.url());
        return root.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
