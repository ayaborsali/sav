package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * La paire de clés VAPID du portail : celle qui signe chaque notification et que
 * le navigateur a enregistrée à l'abonnement.
 *
 * Elle est gardée en base, pas dans une variable d'environnement : régénérer la
 * paire invaliderait tous les abonnements déjà pris, et un redéploiement Render
 * ne doit pas avoir cet effet. Une seule ligne, id « vapid ».
 */
@Entity
@Table(name = "push_vapid_keys")
@Getter @Setter @NoArgsConstructor
public class PushVapidKey {

    @Id
    @Column(length = 20)
    private String id;

    /** 65 octets (point non compressé P-256), base64url sans remplissage. */
    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    /** 32 octets (le scalaire privé), base64url sans remplissage. */
    @Column(name = "private_key", nullable = false, columnDefinition = "TEXT")
    private String privateKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
