package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * La facture : la créance née d'un bon de livraison signé (I01).
 *
 * Le portail chiffrait le travail sans jamais savoir si l'argent était venu. Cette
 * table est le chaîton manquant : le bus est rendu et le client a signé le bon
 * ({@code BonLivraisonService}), le guichet émet la facture, et l'argent qui rentre
 * se note en {@link Encaissement}s jusqu'à solder la créance.
 *
 * Trois règles tiennent ici, et aucune ne se tape au clavier :
 *
 * 1. <b>elle naît d'un BL signé, et reprend le devis accepté en amont.</b> {@code blId}
 *    est unique — deux factures pour un même bus rendu, c'est un client qui paie deux
 *    fois. {@code devisId} est la trace du papier dont l'argent vient : les lignes sont
 *    recopiées, pas rappelées, comme la chaîne des papiers (V35, V46).
 * 2. <b>l'état ne se stocke pas.</b> Émise / partielle / payée / impayée se calcule côté
 *    serveur ({@code FactureService}) depuis la somme des encaissements, {@code totalTTC}
 *    et {@code dueOn}. Un état écrit à la main serait un « payé » que personne n'a encaissé.
 * 3. <b>les dates sont celles du clic.</b> {@code issuedOn} est posée par le serveur à
 *    l'émission ; {@code dueOn} est l'échéance convenue (défaut : émission + 30 jours).
 *    La leçon de V25 / V36 / V39 : une date tapée à la main est une date qu'on recule.
 *
 * Aucune clé étrangère (choix de la maison) : {@code blId}, {@code devisId},
 * {@code clientId}, {@code vehicleId} sont des colonnes simples. C'est le service qui
 * refuse de facturer sans devis accepté, pas la base qui contraint.
 */
@Entity
@Table(name = "factures")
@Getter @Setter @NoArgsConstructor
public class Facture {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, unique = true, length = 50)
    private String number;

    /** Le bon de livraison signé dont cette facture est née. Unique : un BL, une facture. */
    @Column(name = "bl_id", nullable = false, unique = true, length = 50)
    private String blId;

    /** Le devis accepté en amont dont l'argent est recopié. Une trace, pas un lien vivant. */
    @Column(name = "devis_id", length = 50)
    private String devisId;

    @Column(name = "client_id", length = 50)
    private String clientId;

    @Column(name = "vehicle_id", length = 50)
    private String vehicleId;

    /** Le jour de l'émission, posé par le serveur au clic « Facturer ». */
    @Column(name = "issued_on")
    private LocalDate issuedOn;

    /** L'échéance convenue. Défaut posé par le serveur : émission + 30 jours. */
    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "total_ht")
    private Double totalHT;

    @Column(name = "total_tva")
    private Double totalTVA;

    @Column(name = "total_ttc")
    private Double totalTTC;

    /** L'adresse du compte du guichet qui a émis la facture, lue dans le jeton. */
    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Les lignes recopiées du devis accepté, dans l'ordre du papier. Comme
     * {@code maintenance_details} : un prix qui change en amont ne réécrit pas la
     * facture que le client a reçue.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "facture_lines", joinColumns = @JoinColumn(name = "facture_id"))
    @OrderColumn(name = "line_order")
    private List<FactureLine> lines = new ArrayList<>();
}