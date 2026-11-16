package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.InterventionAlert;
import tn.higer.sav_backend.model.InterventionAlertKind;

import java.util.List;

public interface InterventionAlertRepository extends JpaRepository<InterventionAlert, String> {

    /** Tableau de bord de l'atelier : les alertes encore ouvertes, la plus ancienne d'abord. */
    List<InterventionAlert> findByResolvedFalseOrderByDetectedAtAsc();

    /** Fiche d'une sortie : tout ce qui a été signalé sur elle, ouvert ou non. */
    List<InterventionAlert> findByInterventionIdOrderByDetectedAtDesc(String interventionId);

    /** Le scan ne repose pas la même question vingt fois : une seule alerte ouverte par règle. */
    boolean existsByInterventionIdAndKindAndResolvedFalse(String interventionId, InterventionAlertKind kind);

    /** Résolution automatique : la situation s'est réglée, l'alerte ouverte doit se fermer. */
    List<InterventionAlert> findByInterventionIdAndResolvedFalse(String interventionId);
}
