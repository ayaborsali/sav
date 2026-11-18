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
 * Le départ prévu dépassé : la seule annonce qui naît du temps qui passe.
 *
 * Ce qui doit tenir :
 *  - un départ prévu à 8 h ne sonne pas à 8 h 01 (le quart d'heure de grâce) ;
 *  - le même dossier, à la même heure prévue, ne sonne qu'une fois ;
 *  - une annonce que personne n'a reçue n'est pas notée — elle sonnera au tour
 *    suivant, ou le jour où le technicien s'abonne.
 */
@ExtendWith(MockitoExtension.class)
class DepartureLatePushServiceTest {

    @Mock private MissionGroupDossierRepository dossiers;
    @Mock private PushSentRepository sent;
    @Mock private PushNotificationService push;

    private DepartureLatePushService service() {
        return new DepartureLatePushService(dossiers, sent, push, 15, true);
    }

    private static MissionGroupDossier enRetard() {
        MissionGroupDossier d = new MissionGroupDossier();
        d.setId("mgd-1");
        d.setNumber("1740");
        d.setClientName("SRT Sousse");
        d.setTechnicianUserId("usr-tech");
        d.setTechnicianName("Moez");
        d.setPlannedDepartureAt(LocalDateTime.now().minusHours(2).withNano(0));
        return d;
    }

    @Test
    void un_depart_passe_fait_sonner_et_se_note_une_fois() {
        MissionGroupDossier dossier = enRetard();
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNullAndPlannedDepartureAtBefore(any()))
                .thenReturn(List.of(dossier));
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
        assertEquals("/?dossier=mgd-1", message.getValue().url());
        assertTrue(message.getValue().body().contains("SRT Sousse"));
        assertTrue(message.getValue().body().contains("pas pointé"));
    }

    @Test
    void un_envoi_rate_ne_se_note_pas_et_reste_a_faire() {
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNullAndPlannedDepartureAtBefore(any()))
                .thenReturn(List.of(enRetard()));
        when(sent.existsByKindAndTargetIdAndReference(eq("DEPART"), eq("mgd-1"), anyString()))
                .thenReturn(false);
        when(push.deliver(eq("usr-tech"), any())).thenReturn(false);

        assertEquals(0, service().scan());
        // Sans téléphone joint, la ligne ne s'écrit pas : l'annonce sera refaite.
        verify(sent, never()).save(any(PushSent.class));
    }

    @Test
    void une_annonce_deja_recue_ne_sonne_plus() {
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNullAndPlannedDepartureAtBefore(any()))
                .thenReturn(List.of(enRetard()));
        when(sent.existsByKindAndTargetIdAndReference(eq("DEPART"), eq("mgd-1"), anyString()))
                .thenReturn(true);

        assertEquals(0, service().scan());
        verify(push, never()).deliver(any(), any());
    }

    @Test
    void la_grace_d_un_quart_d_heure_est_demandee_au_repository() {
        when(dossiers.findByReturnedAtIsNullAndDepartedAtIsNullAndPlannedDepartureAtBefore(any()))
                .thenReturn(List.of());

        service().scan();

        ArgumentCaptor<LocalDateTime> seuil = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dossiers)
                .findByReturnedAtIsNullAndDepartedAtIsNullAndPlannedDepartureAtBefore(seuil.capture());
        // Tout ce qui est prévu avant now-15min est en retard ; 14 minutes ne suffit pas.
        assertTrue(seuil.getValue().isBefore(LocalDateTime.now().minusMinutes(14)));
        assertTrue(seuil.getValue().isAfter(LocalDateTime.now().minusMinutes(16)));
    }
}
