package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Une photo du terrain reçue de la PWA.
 *
 * L'identifiant vient de l'appareil (UUID) : il rend l'envoi idempotent. Si la
 * réponse du serveur se perd et que la PWA renvoie la même photo, la ligne
 * existe déjà et rien n'est dupliqué. Le client donne l'heure de la prise, le
 * serveur garde la sienne au versement : les deux restent distinctes.
 */
@Entity
@Table(name = "images")
@Getter @Setter @NoArgsConstructor
public class ImageEntity {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "client_created_at")
    private LocalDateTime clientCreatedAt;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "uploaded_by", length = 255)
    private String uploadedBy;

    /** L'image elle-même, en `bytea` PostgreSQL. */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "data", nullable = false, columnDefinition = "bytea")
    private byte[] data;
}
