package tn.higer.sav_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.higer.sav_backend.model.MissionGroupDossier;

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

    boolean existsByNumberIgnoreCase(String number);

    /** Tous les numéros déjà écrits : le prochain se calcule dessus, sans table de compteur. */
    @Query("select d.number from MissionGroupDossier d")
    List<String> findAllNumbers();
}
