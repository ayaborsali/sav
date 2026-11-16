package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.higer.sav_backend.model.InterventionPrime;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InterventionPrimeRepository extends JpaRepository<InterventionPrime, String> {

    /** Une ligne par sortie : c'est là que le double paiement se refuse. */
    Optional<InterventionPrime> findByInterventionId(String interventionId);

    /** Écran des primes de la période, dans l'ordre de validation. */
    List<InterventionPrime> findByValidatedAtBetweenOrderByValidatedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Ce qui est déjà engagé sur la période, demandé avant de valider une ligne de plus. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM InterventionPrime p"
            + " WHERE p.validatedAt BETWEEN :from AND :to")
    BigDecimal sumAmountBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
