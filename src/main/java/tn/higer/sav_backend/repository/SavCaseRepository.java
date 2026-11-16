package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SavCaseRepository extends JpaRepository<SavCase, String> {

    boolean existsByCaseNumberIgnoreCase(String caseNumber);

    boolean existsByCaseNumberIgnoreCaseAndIdNot(String caseNumber, String id);

    /** Les enfants d'un dossier, dans l'ordre où ils ont été ouverts. */
    List<SavCase> findByParentCaseIdOrderByCreatedAtAsc(String parentCaseId);

    /**
     * Les enfants de toute une page de dossiers, en une seule requête. La liste des
     * dossiers affiche « cet appel a déjà donné un claim » sur la ligne : le faire
     * dossier par dossier en enverrait vingt.
     */
    List<SavCase> findByParentCaseIdIn(Collection<String> parentCaseIds);

    /** Le claim déjà né de cette réclamation, s'il existe — pour le nommer dans le refus. */
    Optional<SavCase> findFirstByParentCaseIdAndType(String parentCaseId, SavCaseType type);

    /**
     * Le dossier qualité déjà né de cette sortie, s'il existe. La déclaration se fait
     * un clic sur la ligne de la sortie : deux clics ne doivent pas donner deux
     * dossiers pour la même anomalie vue devant le même bus.
     */
    Optional<SavCase> findFirstByDeclaredFromInterventionIdOrderByCreatedAtDesc(String interventionId);

    /** Les déclarations à détacher quand la sortie qui les a vues part (V34, le ménage de V33). */
    List<SavCase> findByDeclaredFromInterventionId(String interventionId);

    /**
     * Les dossiers rattachés à des forfaits vendus, en une seule requête. Le bandeau
     * des rappels s'en sert pour dire « une visite est déjà ouverte sur ce palier » :
     * la question se pose pour chaque ligne ouverte, et la poser ligne par ligne
     * enverrait autant de requêtes que de rappels.
     */
    List<SavCase> findBySoldPlanIdInOrderByCreatedAtDesc(Collection<String> soldPlanIds);

    /**
     * TOUS les numéros d'une famille (préfixe « SAV-WAR-2026- »). Le plus haut se
     * décide en Java, dans l'ordre numérique : trier par longueur en SQL retiendrait
     * « -0028 » avant « -029 » et redonnerait un numéro déjà posé.
     */
    @Query("SELECT c.caseNumber FROM SavCase c WHERE c.caseNumber LIKE CONCAT(:prefix, '%')")
    List<String> findNumbersByPrefix(String prefix);
}
