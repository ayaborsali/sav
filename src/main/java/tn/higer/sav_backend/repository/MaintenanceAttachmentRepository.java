package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.MaintenanceAttachment;

import java.util.List;

public interface MaintenanceAttachmentRepository extends JpaRepository<MaintenanceAttachment, String> {

    /** Les pièces d'un document, dans l'ordre où elles sont arrivées. */
    List<MaintenanceAttachment> findByMaintenanceItemIdOrderByUploadedAtAsc(String maintenanceItemId);

    /** Le même fichier, déjà versé sur ce document. */
    boolean existsByMaintenanceItemIdAndSha256(String maintenanceItemId, String sha256);

    /** Combien de preuves attachées : l'accord du client exige au moins le scan signé. */
    long countByMaintenanceItemId(String maintenanceItemId);

    void deleteByMaintenanceItemId(String maintenanceItemId);
}
