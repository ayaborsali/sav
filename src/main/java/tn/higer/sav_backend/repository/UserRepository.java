package tn.higer.sav_backend.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * La ligne de l'homme, tenue jusqu'à la validation de la transaction. Deux
     * guichets qui préparent en même temps ne peuvent pas réserver le même homme :
     * le second attend, puis relit les disponibilités à jour.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);

    Optional<AppUser> findByEmailIgnoreCase(String email);

    Optional<AppUser> findByResetTokenHash(String resetTokenHash);

    boolean existsByEmailIgnoreCase(String email);

    /** Nombre d'administrateurs actuellement actifs (protection anti-verrouillage). */
    long countByRoleAndActiveTrue(Role role);

    /** Les gens que le dépannage et les dossiers SAV peuvent choisir : les comptes techniciens actifs. */
    List<AppUser> findByRoleAndActiveTrueOrderByNameAsc(Role role);
}