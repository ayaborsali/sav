package tn.higer.sav_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.higer.sav_backend.model.MissionGroupDossier;

import java.time.LocalDateTime;
import java.util.List;

public interface MissionGroupDossierRepository extends JpaRepository<MissionGroupDossier, String> {

    /** Le dernier papier préparé en tête : c'est celui que l'atelier cherche le matin. */
    Page<MissionGroupDossier> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Ce que voit le technicien : les dossiers où il est l'homme affecté, et rien
     * d'autre. Le serveur filtre ; l'écran ne reçoit pas ce qu'il n'a pas à lire.
     */
    Page<MissionGroupDossier> findByTechnicianUserIdOrderByCreatedAtDesc(String technicianUserId,
                                                                         Pageable pageable);

    /**
     * Le suivi du terrain : les dossiers du technicien qui ne sont pas encore
     * rentrés. Un dossier rentré a rendu ses comptes et n'a plus rien à annoncer.
     */
    List<MissionGroupDossier> findByTechnicianUserIdAndReturnedAtIsNullOrderByCreatedAtDesc(
            String technicianUserId);

    boolean existsByNumberIgnoreCase(String number);

    /**
     * Les dossiers dont l'heure de départ prévue est passée sans que le départ
     * réel soit pointé — la question que la poussée repose à l'homme affecté.
     */
    List<MissionGroupDossier> findByReturnedAtIsNullAndDepartedAtIsNullAndPlannedDepartureAtBefore(
            LocalDateTime seuil);

    /** Tous les numéros déjà écrits : le prochain se calcule dessus, sans table de compteur. */
    @Query("select d.number from MissionGroupDossier d")
    List<String> findAllNumbers();
}
