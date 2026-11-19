package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.higer.sav_backend.model.MissionGroupDossier;
import tn.higer.sav_backend.model.PushSent;
import tn.higer.sav_backend.repository.MissionGroupDossierRepository;
import tn.higer.sav_backend.repository.PushSentRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les deux rappels qui naissent du temps qui passe : départ prévu dépassé et
 * retour prévu dépassé.
 *
 * Ce qui doit tenir, pour chacun :
 *  - une heure prévue il y a deux heures ne sonne pas à l'instant (le quart
 *    d'heure de grâce est demandé au repository) ;
 *  - le même dossier, à la même heure prévue, ne sonne qu'une fois ;
 *  - une annonce que personne n'a reçue n'est pas notée — elle sonnera au tour
 *    suivant, ou le jour où le technicien s'abonne.
 */
@ExtendWith(MockitoExtension.class)
class MissionReminderPushServiceTest {

    @Mock private MissionGroupDossierRepository dossiers;
    @Mock private PushSentRepository sent;
    @Mock private PushNotificationService push;

    private MissionReminderPushService service() {
        return new MissionReminderPushService(dossiers, sent, push, 15, true);
    }

    private static MissionGroupDossier enRetardDeDepart() {
        MissionGroupDossier d = new MissionGroupDossier();
        d.setId("mgd-1");
        d.setNumber("1740");
        d.setClientName("SRT Sousse");
        d.setTechnicianUserId("usr-tech");
        d.setTechnicianName("Moez");
        d.setPlannedDepartureAt(LocalDateTime.now().minusHours(2).withNano(0));
        return d;
    }

    private static MissionGroupDossier enRetardDeRetour() {
        MissionGroupDossier d = new MissionGroupDossier();
        d.setId("mgd-2");
        d.setNumber("1741");
        d.setClientName("STIA Kairouan");
        d.setTechnicianUserId("usr-tech");
        d.setTechnicianName("Moez");
        d.setPlannedDepartureAt(LocalDateTime.now().minusHours(6).withNano(0));
        d.setDepartedAt(LocalDateTime.now().minusHours(5).withNano(0));
        d.setPlannedReturnAt(LocalDateTime.now().minusHours(1).withNano(0));
        return d;
    }

    private void aucunDepartEnRetard() {
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNullAndCancelledAtIsNullAndPlannedDepartureAtBefore(any()))
                .thenReturn(List.of());
    }

    private void aucunRetourEnRetard() {
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNotNullAndCancelledAtIsNullAndPlannedReturnAtBefore(any()))
                .thenReturn(List.of());
    }

    @Test
    void un_depart_passe_fait_sonner_et_se_note_une_fois() {
        MissionGroupDossier dossier = enRetardDeDepart();
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNullAndCancelledAtIsNullAndPlannedDepartureAtBefore(any()))
                .thenReturn(List.of(dossier));
        aucunRetourEnRetard();
        when(sent.existsByKindAndTargetIdAndReference(eq("DEPART"), eq("mgd-1"), anyString()))
                .thenReturn(false);
        when(push.deliver(eq("usr-tech"), any())).thenReturn(true);

        assertEquals(1, service().scan());

        ArgumentCaptor<PushSent> trace = ArgumentCaptor.forClass(PushSent.class);
        verify(sent).save(trace.capture());
        assertEquals("DEPART", trace.getValue().getKind());
        assertEquals("mgd-1", trace.getValue().getTargetId());
        // La référence est l'heure prévue : un départ reporté par l'atelier sera
        // une nouvelle annonce, le même horaire ne resonnera pas.
        assertEquals(dossier.getPlannedDepartureAt().toString(), trace.getValue().getReference());

        ArgumentCaptor<PushNotificationService.Message> message =
                ArgumentCaptor.forClass(PushNotificationService.Message.class);
        verify(push).deliver(eq("usr-tech"), message.capture());
        assertEquals("Départ prévu dépassé", message.getValue().title());
        assertEquals("/?dossier=mgd-1", message.getValue().url());
        assertEquals("depart-mgd-1", message.getValue().tag());
        assertTrue(message.getValue().body().contains("SRT Sousse"));
        assertTrue(message.getValue().body().contains("départ réel n'est pas pointé"));
    }

    @Test
    void un_retour_passe_fait_sonner_et_se_note_une_fois() {
        MissionGroupDossier dossier = enRetardDeRetour();
        aucunDepartEnRetard();
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNotNullAndCancelledAtIsNullAndPlannedReturnAtBefore(any()))
                .thenReturn(List.of(dossier));
        when(sent.existsByKindAndTargetIdAndReference(eq("RETOUR"), eq("mgd-2"), anyString()))
                .thenReturn(false);
        when(push.deliver(eq("usr-tech"), any())).thenReturn(true);

        assertEquals(1, service().scan());

        ArgumentCaptor<PushSent> trace = ArgumentCaptor.forClass(PushSent.class);
        verify(sent).save(trace.capture());
        assertEquals("RETOUR", trace.getValue().getKind());
        assertEquals("mgd-2", trace.getValue().getTargetId());
        assertEquals(dossier.getPlannedReturnAt().toString(), trace.getValue().getReference());

        ArgumentCaptor<PushNotificationService.Message> message =
                ArgumentCaptor.forClass(PushNotificationService.Message.class);
        verify(push).deliver(eq("usr-tech"), message.capture());
        assertEquals("Retour prévu dépassé", message.getValue().title());
        assertEquals("/?dossier=mgd-2", message.getValue().url());
        assertEquals("retour-mgd-2", message.getValue().tag());
        assertTrue(message.getValue().body().contains("STIA Kairouan"));
        assertTrue(message.getValue().body().contains("retour réel n'est pas pointé"));
    }

    @Test
    void un_depart_et_un_retour_en_retard_font_deux_annonces() {
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNullAndCancelledAtIsNullAndPlannedDepartureAtBefore(any()))
                .thenReturn(List.of(enRetardDeDepart()));
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNotNullAndCancelledAtIsNullAndPlannedReturnAtBefore(any()))
                .thenReturn(List.of(enRetardDeRetour()));
        when(sent.existsByKindAndTargetIdAndReference(anyString(), anyString(), anyString()))
                .thenReturn(false);
        when(push.deliver(eq("usr-tech"), any())).thenReturn(true);

        assertEquals(2, service().scan());
        verify(sent, org.mockito.Mockito.times(2)).save(any(PushSent.class));
    }

    @Test
    void un_envoi_rate_ne_se_note_pas_et_reste_a_faire() {
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNullAndCancelledAtIsNullAndPlannedDepartureAtBefore(any()))
                .thenReturn(List.of(enRetardDeDepart()));
        aucunRetourEnRetard();
        when(sent.existsByKindAndTargetIdAndReference(eq("DEPART"), eq("mgd-1"), anyString()))
                .thenReturn(false);
        when(push.deliver(eq("usr-tech"), any())).thenReturn(false);

        assertEquals(0, service().scan());
        // Sans téléphone joint, la ligne ne s'écrit pas : l'annonce sera refaite.
        verify(sent, never()).save(any(PushSent.class));
    }

    @Test
    void une_annonce_deja_recue_ne_sonne_plus() {
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNullAndCancelledAtIsNullAndPlannedDepartureAtBefore(any()))
                .thenReturn(List.of(enRetardDeDepart()));
        aucunRetourEnRetard();
        when(sent.existsByKindAndTargetIdAndReference(eq("DEPART"), eq("mgd-1"), anyString()))
                .thenReturn(true);

        assertEquals(0, service().scan());
        verify(push, never()).deliver(any(), any());
    }

    @Test
    void un_retour_deja_annonce_ne_sonne_plus() {
        aucunDepartEnRetard();
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNotNullAndCancelledAtIsNullAndPlannedReturnAtBefore(any()))
                .thenReturn(List.of(enRetardDeRetour()));
        when(sent.existsByKindAndTargetIdAndReference(eq("RETOUR"), eq("mgd-2"), anyString()))
                .thenReturn(true);

        assertEquals(0, service().scan());
        verify(push, never()).deliver(any(), any());
    }

    @Test
    void la_grace_d_un_quart_d_heure_est_demandee_au_repository() {
        aucunDepartEnRetard();
        aucunRetourEnRetard();

        service().scan();

        ArgumentCaptor<LocalDateTime> seuil = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dossiers)
                .findByReturnedAtIsNullAndDepartedAtIsNullAndCancelledAtIsNullAndPlannedDepartureAtBefore(
                        seuil.capture());
        // Tout ce qui est prévu avant now-15min est en retard ; 14 minutes ne suffit pas.
        assertTrue(seuil.getValue().isBefore(LocalDateTime.now().minusMinutes(14)));
        assertTrue(seuil.getValue().isAfter(LocalDateTime.now().minusMinutes(16)));
    }
}
