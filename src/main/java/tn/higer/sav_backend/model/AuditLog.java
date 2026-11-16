package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Piste d'audit : une ligne par opération qui modifie l'état (création, mise à jour,
 * suppression, validation, refus) sur les cinq entités métier. Les lectures (GET) ne sont
 * jamais auditées. L'auteur est résolu comme dans SessionJwtConverter : le sujet du jeton
 * porte l'identifiant utilisateur et la claim « email » son adresse.
 */
@Entity
@Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    private String actorUserId;

    private String actorEmail;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(nullable = false, length = 40)
    private String entityType;

    @Column(length = 50)
    private String entityId;

    @Column(length = 1000)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime timestamp;
}
