package tn.higer.sav_backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * L'argent qui rentre sur une facture (I01).
 *
 * Une facture se paie en un ou plusieurs versements ; c'est la somme de ces lignes
 * qui décide l'état (émise / partielle / payée / impayée), calculé côté serveur. Un
 * encaissement n'est pas une case « payé » qu'on coche : c'est un montant, un jour,
 * un mode et quelqu'un qui l'a écrit — la preuve que l'argent est venu.
 *
 * La date ({@code paidOn}) est le jour du clic, posée par le serveur, jamais une case
 * du formulaire : un encaissement antidaté est un « payé » qu'on recule quand la
 * créance se discute. {@code recordedBy} est l'adresse du guichet lue dans le jeton.
 *
 * Aucune clé étrangère vers {@code factures} (choix de la maison) : {@code factureId}
 * est une colonne simple, et c'est {@code FactureService} qui vérifie que la facture
 * existe et que le montant ne dépasse pas ce qui reste dû.
 */
@Entity
@Table(name = "encaissements")
@Getter @Setter @NoArgsConstructor
public class Encaissement {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "facture_id", nullable = false, length = 50)
    private String factureId;

    @Column(nullable = false)
    private Double amount;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    /** Le mode écrit par le guichet : « espèces », « chèque n°… », « virement ». */
    @Column(length = 120)
    private String mode;

    @Column(name = "recorded_by", length = 255)
    private String recordedBy;

    @Column(columnDefinition = "TEXT")
    private String note;
}