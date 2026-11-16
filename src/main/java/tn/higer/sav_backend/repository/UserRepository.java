package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    Optional<AppUser> findByResetTokenHash(String resetTokenHash);

    boolean existsByEmailIgnoreCase(String email);

    /** Nombre d'administrateurs actuellement actifs (protection anti-verrouillage). */
    long countByRoleAndActiveTrue(Role role);

    /** Les gens que le dépannage et les dossiers SAV peuvent choisir : les comptes techniciens actifs. */
    List<AppUser> findByRoleAndActiveTrueOrderByNameAsc(Role role);
}