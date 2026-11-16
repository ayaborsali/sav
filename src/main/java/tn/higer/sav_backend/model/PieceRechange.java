package tn.higer.sav_backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Une pièce du catalogue de rechange : ce qu'elle coûte et où elle se reprend.
 *
 * Ce n'est PAS une pièce posée sur un bus ({@link ItemPart}, table `item_parts`) :
 * celle-là raconte un travail fait, celle-ci raconte ce que le catalogue propose.
 * Un ordre peut très bien poser une pièce retirée du catalogue depuis deux ans.
 *
 * {@code prixRevient} est une colonne, mais personne ne l'écrit :
 * {@code PieceCatalogService} la recalcule à chaque enregistrement depuis le prix
 * Ex Works et le coefficient de revient. Elle est stockée pour qu'une liste soit
 * triable et qu'un devis relitu six mois après retrouve le chiffre qui a servi —
 * pas pour que l'écran décide du prix.
 *
 * Le catalogue porte <b>deux</b> coefficients, chacun sa colonne : celui du revient
 * (transport, dédouanement, devise) et celui de la vente (la marge). Le prix de
 * vente se calcule donc lui aussi — prix de revient × coefficient de vente — et
 * {@code PieceCatalogService} le recalcule à chaque enregistrement. L'atelier a
 * tranché le 06/11 : un taux posé sur la pièce vaut mieux qu'un prix retapé à la
 * main à chaque fois, parce qu'il se relit et se corrige.
 *
 * {@code coefficientVente} est obligatoire à l'écriture mais nullable en base : les
 * pièces entrées sous V43 n'en portent pas, et un NOT NULL les rendrait
 * illisibles. Vide se lit « entrée avant la règle », jamais « coefficient refusé ».
 *
 * {@code famille} ne reste vide nulle part, au contraire : c'est le « type PR » que
 * l'atelier a demandé (Mécanique, Suspension, Électricité, Freinage, Boîte de
 * vitesse, Pont arrière, Divers). Une famille n'est pas un montant — elle se voit
 * aussi bien à un compte sans droit aux chiffres commerciaux — et elle ne se
 * devine pas : « Divers » est un choix de l'atelier, jamais le rebut d'un mot que
 * le serveur n'a pas reconnu.
 *
 * Le coefficient de revient est saisi pièce par pièce, et c'est le fond du
 * métier : c'est lui qui porte le transport, le dédouanement et la conversion de
 * devise quand le document Higer annonce un prix Ex Works en euros. Le portail
 * n'invente aucun taux ; {@code devise} dit celle du prix d'origine, le prix de
 * revient calculé est en dinars.
 */
@Entity
@Table(name = "piece_rechanges")
@Getter @Setter @NoArgsConstructor
public class PieceRechange {

    /** Les devises que le portail sait étiqueter. Liste fermée : une devise inventée fausse le prix d'un devis. */
    public static final String DEVISE_PAR_DEFAUT = "EUR";
    public static final String REGEX_DEVISE = "(?i)^(EUR|USD|TND|CNY)?$";
    public static final Set<String> DEVISES = Set.of("EUR", "USD", "TND", "CNY");

    @Id
    @Column(length = 50)
    private String id;

    /** La référence frappée sur la pièce. Unique : deux lignes pour la même pièce = deux prix chez le client. */
    @Column(nullable = false, unique = true, length = 160)
    private String reference;

    @Column(name = "designation_fr", nullable = false, length = 300)
    private String designationFr;

    @Column(name = "designation_en", length = 300)
    private String designationEn;

    /** Prix annoncé par le fournisseur, dans {@link #devise}. */
    @Column(name = "prix_ex_works", nullable = false, precision = 12, scale = 3)
    private BigDecimal prixExWorks;

    @Column(name = "coefficient_revient", nullable = false, precision = 10, scale = 4)
    private BigDecimal coefficientRevient;

    /**
     * Le coefficient de vente, saisi pièce par pièce. Obligatoire à l'écriture,
     * nullable en base pour les pièces d'avant V47 — voir l'en-tête de la classe.
     */
    @Column(name = "coefficient_vente", precision = 10, scale = 4)
    private BigDecimal coefficientVente;

    @Column(name = "prix_revient", nullable = false, precision = 12, scale = 3)
    private BigDecimal prixRevient;

    /**
     * Le prix de vente calculé par le serveur : prix de revient × coefficient de
     * vente, au millime. Stocké pour qu'une liste se trie et qu'un papier d'avant
     * retrouve le chiffre du jour.
     */
    @Column(name = "prix_vente", precision = 12, scale = 3)
    private BigDecimal prixVente;

    @Column(length = 160)
    private String fournisseur;

    /**
     * La famille de la pièce — le « type PR » de l'atelier. Une seule, obligatoire,
     * choisie parmi les sept de {@link PieceFamily} : un classeur qui arrive sans,
     * ou avec un mot hors liste, est refusé ligne par ligne plutôt que rangé en
     * « Divers » tout seul.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "famille", nullable = false, length = 32)
    private PieceFamily famille;

    /**
     * Le « type » de l'atelier : {@link PieceType#MECANIQUE} ou {@link PieceType#ELECTRIQUE}.
     * Plus grossier que la famille, et non obligatoire : vide se lit « à classer »,
     * jamais « Mécanique par défaut ».
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type_piece", length = 16)
    private PieceType typePiece;

    /** Devise d'origine du prix Ex Works : EUR par défaut, ou l'une des quatre devises connues. */
    @Column(length = 3, nullable = false)
    private String devise = DEVISE_PAR_DEFAUT;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
