package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.Encaissement;

import java.util.List;

public interface EncaissementRepository extends JpaRepository<Encaissement, String> {

    List<Encaissement> findByFactureIdOrderByPaidOnAsc(String factureId);
}