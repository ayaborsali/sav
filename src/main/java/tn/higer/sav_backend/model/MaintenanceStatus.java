package tn.higer.sav_backend.model;

/**
 * L'avancement d'un papier de l'atelier. Les mots sont ici, une seule fois :
 * l'écran les rend tels que le serveur les écrit. Une liste de libellés tenue en
 * plus du côté de l'interface finirait par se contredire avec celle-ci, et un
 * « delivered » laissé sur une pastille ne dit pas à l'atelier si le bus est rendu.
 *
 * <b>Le devis ne vit pas dans le même temps que l'ordre et le bon de livraison.</b>
 * Un devis se signe chez le client, pas à l'atelier : il part ({@link #SENT}), il
 * revient accepté ({@link #VALIDATED}) ou refusé ({@link #REFUSED}). Ces trois
 * états n'existent que pour lui, et {@code DevisService} est la seule porte qui les
 * écrit — jamais le formulaire. Le mot qui compte pour l'argent est
 * {@link #VALIDATED} : c'est là que le client a dit oui, et un ordre de travail ne
 * s'ouvre qu'après cet accord ({@code MaintenanceChainService.ordreDepuisDevis}).
 *
 * {@link #CANCELLED} n'est pas un effacement : le papier reste, il dit que le
 * travail n'aura pas lieu. C'est aussi le seul geste qui libère un palier de forfait
 * coché à tort.
 *
 * <b>Le bon de livraison ne vit que de deux mots.</b> Un BL part en brouillon
 * ({@link #DRAFT}) et se termine chez le client ({@link #SIGNED}) : il ne s'envoie
 * pas, ne s'accepte pas, ne se refuse pas — il se signe. {@code BonLivraisonService}
 * est la seule porte qui écrit {@link #SIGNED}, et le scan signé du client doit être
 * joint au papier avant le clic. Un BL signé verrouille le BL et l'ordre dont il est
 * né : le client a tenu les deux papiers en main.
 *
 * ⚠️ Ajouter un état ici oblige à une migration : la contrainte CHECK de
 * {@code maintenance_items.status} énumère les mots que PostgreSQL accepte (leçon
 * de V30, reprise en V36 et en V39), et {@code ContraintesDeStatutsEnBaseTest} relit
 * les deux listes pour qu'un écart se voie dans un test plutôt que sur un écran en 500.
 */
public enum MaintenanceStatus {
    DRAFT("Brouillon"),
    SENT("Envoyé au client"),
    VALIDATED("Accepté"),
    REFUSED("Refusé"),
    IN_PROGRESS("En cours d'atelier"),
    DELIVERED("Livré"),
    COMPLETED("Terminé"),
    SIGNED("Signé"),
    CANCELLED("Annulé");

    private final String label;

    MaintenanceStatus(String label) {
        this.label = label;
    }

    /** Le mot que l'atelier lit à l'écran. */
    public String label() {
        return label;
    }

    /**
     * Le mot pour CE papier. Une valeur en base n'a qu'un nom, mais l'atelier n'a pas
     * le même vocabulaire d'un bout à l'autre de la chaîne : le même `DRAFT` est un
     * devis « brouillon » que personne n'a encore envoyé, et un ordre « à faire » que
     * l'atelier n'a pas encore commencé. Le maillot de l'ordre se lit sur sa ligne,
     * et « brouillon » ne se corrige pas à l'atelier.
     *
     * Rien n'est inventé ici : ces trois mots étaient déjà écrits dans les messages de
     * refus et dans les maquettes. Seule la pastille change de nom, jamais la colonne.
     */
    public String label(MaintenanceType type) {
        if (type != MaintenanceType.OPERATION) {
            return label;
        }
        return switch (this) {
            case DRAFT -> "À faire";
            case IN_PROGRESS -> "En cours";
            case COMPLETED -> "Terminé";
            default -> label;
        };
    }
}
