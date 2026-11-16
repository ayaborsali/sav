package tn.higer.sav_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.MissionState;

import java.util.List;
import java.util.Optional;

public interface MissionRepository extends JpaRepository<Mission, String> {

    boolean existsByNumberIgnoreCase(String number);

    /** La page des missions, les plus récentes en tête : c'est l'ordre du guichet le matin. */
    Page<Mission> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * TOUS les numéros MT- du compteur courant. Le plus haut se décide en Java, pas
     * en SQL : trier par longueur rendrait « -0028 » avant « -029 » et redonnerait un
     * numéro déjà posé (piège corrigé sur les dossiers SAV).
     */
    @Query("SELECT m.number FROM Mission m WHERE m.number LIKE CONCAT(:prefix, '%')")
    List<String> findNumbersByPrefix(String prefix);

    /**
     * La demande déjà ouverte sur ce dossier, dans l'ordre du temps. Une case
     * cochée deux fois, ou cochée-décochée-recochée, ne doit pas faire deux
     * missions : le premier appel de ce service regarde ce qui court déjà.
     */
    List<Mission> findBySavCaseIdOrderByCreatedAtDesc(String savCaseId);

    /** Idem pour un rappel : une question posée une fois n'attend pas deux réponses. */
    List<Mission> findByAlertIdOrderByCreatedAtDesc(String alertId);

    /**
     * Les demandes qui courent encore, pour le bandeau de chiffres. « Soldée »
     * n'étant jamais stockée, cette liste contient aussi des missions tenues pour
     * rendues : c'est le lecteur qui les reconnaît, et le bandeau passe par lui.
     */
    List<Mission> findByStateInOrderByCreatedAtDesc(List<MissionState> etats);

    /**
     * L'autre moitié du lien : la demande qui a fait sortir cette voiture. Le sens
     * reste unique — c'est la mission qui porte l'identifiant de sa sortie —, l'écran
     * des sorties ne fait que le relire. Une écriture des deux côtés ferait deux
     * liaisons dont aucune n'est garantie de dire la même chose.
     */
    List<Mission> findByInterventionIdIn(List<String> interventionIds);

    Optional<Mission> findByInterventionId(String interventionId);
}
