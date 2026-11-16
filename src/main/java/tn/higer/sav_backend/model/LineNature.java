package tn.higer.sav_backend.model;

/**
 * Ce que chiffre une ligne de devis.
 *
 * Le contrat de maintenance ne couvre QUE la main-d'œuvre : une ligne de pièce, de
 * consommable ou de déplacement sort du contrat, et ce qui sort du contrat se
 * facture au client — donc se devisse avant d'être fait. Sans ce mot sur la
 * ligne, l'atelier ne sait pas ce qu'il chiffre et le client ne sait pas ce
 * qu'il accepte ; le total reste un nombre sans explication.
 *
 * Le nom est posé à la ligne et non au document, parce qu'un devis mélange les
 * trois : une heure de main-d'œuvre, un filtre à 12,500 DT et un déplacement à
 * 25,000 DT tiennent sur le même papier.
 *
 * ⚠️ Les mots que la base accepte sont écrits dans le CHECK de
 * {@code maintenance_details.nature} (V36) : ajouter un état ici oblige à une
 * migration, sinon PostgreSQL refuse la ligne et l'écran part en 500.
 */
public enum LineNature {
    /** Le travail de l'homme, au temps et au taux de l'atelier. */
    LABOR("Main-d'œuvre"),
    /** Une pièce, fournie par Saphir ou par le client — {@link PartSupplier} le dit. */
    PART("Pièce"),
    /** Le trajet de la voiture de déplacement, hors du forfait comme du contrat. */
    TRAVEL("Déplacement");

    private final String label;

    LineNature(String label) {
        this.label = label;
    }

    /** Le mot que l'atelier lit à l'écran. */
    public String label() {
        return label;
    }

    /** Une seule des trois natures a un « fourni par » : la pièce. */
    public boolean estUnePiece() {
        return this == PART;
    }
}
