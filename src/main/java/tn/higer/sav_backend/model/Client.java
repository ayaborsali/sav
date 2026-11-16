package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "clients")
@Getter @Setter @NoArgsConstructor
public class Client {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClientType type;

    private String contactPerson;
    private String email;
    private String phone;
    private String address;
    private String city;

    private LocalDate createdAt;

    @Column(length = 1000)
    private String notes;

    // --- Fiche commerciale (V3), colonnes du fichier Modele_Fichier_Client_Transport ---

    /** Fonction du contact SAV, colonne « Fonction ». */
    @Column(length = 120)
    private String contactRole;

    /** Colonne « Pays », Tunisie par défaut côté interface. */
    @Column(length = 80)
    private String country;

    /** Colonne « Dernier contact ». */
    private LocalDate lastContactAt;

    /** Colonne « Commercial » : interlocuteur Higer côté prestataire. */
    @Column(length = 120)
    private String salesRep;

    /** Colonne « Statut », à ne pas confondre avec l'activation d'un compte utilisateur. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private CommercialStatus commercialStatus;

    /** Colonne « Produits/Services » : bus livrés, contrats d'entretien, formation… */
    @Column(length = 500)
    private String products;

    /** Colonne « CA ». Confidentiel : masqué pour le rôle consultation. */
    @Column(precision = 14, scale = 2)
    private BigDecimal annualRevenue;

    /** Colonne « Conditions paiement ». Confidentiel : masqué pour le rôle consultation. */
    @Column(length = 120)
    private String paymentTerms;

    // --- Identification juridique du client (V5) — nécessaire sur le contrat imprimé.
    // Sans registre de commerce ni matricule fiscal, la partie « client » n'est pas opposable.

    /** Registre de commerce (RC) du client. */
    @Column(length = 60)
    private String tradeRegister;

    /** Matricule fiscal (IMF) du client. */
    @Column(length = 40)
    private String taxId;
}
