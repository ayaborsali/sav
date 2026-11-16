package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.Technician;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TechnicianRepository extends JpaRepository<Technician, String> {

    List<Technician> findAllByOrderByActiveDescNameAsc();

    Optional<Technician> findByUserId(UUID userId);

    /** Les fiches reliées à ces comptes, en une requête : c'est ce que fabrique la liste de choix. */
    List<Technician> findByUserIdIn(Collection<UUID> userIds);

    /**
     * La fiche du même nom, si elle existe déjà. Sert au rattachement d'un compte :
     * une fiche ouverte à la main avant l'arrivée des comptes ne doit pas être
     * doublonnée quand le compte correspondant est choisi.
     */
    Optional<Technician> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, String id);
}
