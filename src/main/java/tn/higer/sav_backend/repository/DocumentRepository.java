package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.Document;

public interface DocumentRepository extends JpaRepository<Document, String> {
}
