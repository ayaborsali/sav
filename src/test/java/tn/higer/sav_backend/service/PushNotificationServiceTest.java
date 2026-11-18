package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.higer.sav_backend.dto.PushDtos.SubscriptionRequest;
import tn.higer.sav_backend.model.PushSubscription;
import tn.higer.sav_backend.model.PushVapidKey;
import tn.higer.sav_backend.repository.PushSubscriptionRepository;
import tn.higer.sav_backend.repository.PushVapidKeyRepository;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Ce qui doit rester vrai quand on fera sonner les téléphones.
 *
 * Trois points, et ce sont ceux qui cassent en silence :
 *  - la paire VAPID se fabrique une seule fois et se garde — en changer rendrait
 *    muets tous les téléphones déjà abonnés ;
 *  - la même adresse ne s'écrit qu'une fois, et change de propriétaire quand un
 *    autre compte se connecte sur ce téléphone ;
 *  - un compte sans téléphone abonné n'envoie rien et ne fabrique pas de clé.
 */
@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock private PushSubscriptionRepository subscriptions;
    @Mock private PushVapidKeyRepository keys;

    private PushNotificationService service() {
        return new PushNotificationService(subscriptions, keys, "https://sav-2dgg.onrender.com");
    }

    @Test
    void la_paire_vapid_est_fabriquee_une_fois_et_gardee_en_base() {
        when(keys.findById("vapid")).thenReturn(Optional.empty());
        when(keys.save(any(PushVapidKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String cle = service().publicKey();

        ArgumentCaptor<PushVapidKey> ligne = ArgumentCaptor.forClass(PushVapidKey.class);
        verify(keys).save(ligne.capture());
        assertEquals("vapid", ligne.getValue().getId());
        assertNotNull(ligne.getValue().getCreatedAt());

        // Le point non compressé d'une courbe P-256 : 65 octets, le premier valant 4.
        byte[] publique = Base64.getUrlDecoder().decode(cle);
        assertEquals(65, publique.length);
        assertEquals(4, publique[0]);
        assertEquals(ligne.getValue().getPublicKey(), cle);

        // Le scalaire privé est encodé tel que la bibliothèque le rend : l'entier
        // signé fait 31 à 33 octets selon son premier octet, et c'est cette même
        // forme qu'elle relit à l'envoi. La clé publique, elle, est fixe.
        int privee = Base64.getUrlDecoder().decode(ligne.getValue().getPrivateKey()).length;
        assertTrue(privee >= 31 && privee <= 33, "clé privée de " + privee + " octets");
    }

    @Test
    void un_telephone_deja_abonne_change_de_proprietaire_au_lieu_de_doubler() {
        PushSubscription existant = new PushSubscription();
        existant.setId("psb-1");
        existant.setUserId("ancien-compte");
        existant.setEndpoint("https://push.example/abc");
        when(subscriptions.findByEndpoint("https://push.example/abc"))
                .thenReturn(Optional.of(existant));

        service().subscribe("nouveau-compte",
                new SubscriptionRequest("https://push.example/abc", "p256dh", "auth", "iPhone"));

        ArgumentCaptor<PushSubscription> ligne = ArgumentCaptor.forClass(PushSubscription.class);
        verify(subscriptions).save(ligne.capture());
        // La même ligne : deux abonnements pour une adresse feraient deux envois.
        assertEquals("psb-1", ligne.getValue().getId());
        assertEquals("nouveau-compte", ligne.getValue().getUserId());
        assertEquals("p256dh", ligne.getValue().getP256dh());
        assertNotNull(ligne.getValue().getLastSeenAt());
    }

    @Test
    void un_compte_sans_telephone_ne_recoit_rien() {
        when(subscriptions.findByUserId("usr-1")).thenReturn(List.of());

        boolean recue = service().deliver("usr-1",
                new PushNotificationService.Message("Dossier 1740", "SRT Sousse", "/?dossier=mgd-1", "dossier-mgd-1"));

        assertFalse(recue);
        // Aucune clé n'a été fabriquée pour un envoi qui n'a pas de destinataire.
        verifyNoInteractions(keys);
    }
}
