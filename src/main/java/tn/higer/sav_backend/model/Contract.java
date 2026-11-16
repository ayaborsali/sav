package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * En-tête d'un contrat de maintenance. Les lignes du contrat ne sont pas stockées ici :
 * ce sont les véhicules dont contract_id pointe cette ligne, chacun avec son
 * prix_unitaire_ht, qui porte ici le forfait HT de la prestation sur ce véhicule.
 * Les totaux sont donc recalculés à la lecture (ContractController),
 * jamais figés dans la base.
 */
@Entity
@Table(name = "contracts")
@Getter @Setter @NoArgsConstructor
public class Contract {

    @Id
    @Column(length = 50)
    private String id;

    /** Numéro généré par le serveur sous la forme CT-HIG-2026-014. */
    @Column(nullable = false, unique = true, length = 40)
    private String number;

    @Column(nullable = false)
    private LocalDate contractDate;

    @Column(nullable = false)
    private String clientId;

    /** Conditions de paiement du contrat (30 jours, virement à la commande…). */
    @Column(length = 120)
    private String paymentTerms;

    @Column(length = 500)
    private String deliveryAddress;

    @Column(length = 1000)
    private String notes;

    // --- Rédaction des clauses (V5) — nullables : null = rédaction standard (ContractClauses).
    // Une zone saisie remplace le texte standard, jamais elle ne le duplique.
    // Un IA (Groq qwen3-32b) peut proposer ces textes via POST /api/contracts/{id}/draft,
    // mais ils ne sont écrits ici qu'après validation humaine. Les montants n'y figurent jamais :
    // ils restent recalculés à la lecture depuis vehicles.unit_price_ht.

    @Column(length = 1500)
    private String objectClause;

    @Column(length = 1000)
    private String deliveryClause;

    @Column(length = 1000)
    private String warrantyClause;

    @Column(length = 1000)
    private String ownershipClause;

    @Column(length = 1000)
    private String disputeClause;

    @Column(length = 2000)
    private String specialTerms;
}
