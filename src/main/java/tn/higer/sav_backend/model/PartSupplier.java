package tn.higer.sav_backend.model;

/**
 * Qui paye la pièce portée sur la ligne.
 *
 * Ce n'est pas une précision de formulaire, c'est l'argent. Le contrat de
 * maintenance oblige le client à fournir lui-même certaines pièces (et les
 * fluides) : une pièce « fournie par le client » ne se facture pas, elle est
 * montée sur le bus par l'atelier. Une pièce « fournie par Saphir » se facture,
 * au tarif catalogue, et se retrouve sur la facture du devis accepté.
 *
 * La case reste obligatoire sur une ligne de pièce, sans valeur par défaut : un
 * « fourni par » laissé vide se lirait plus tard comme une pièce offerte, et
 * personne ne sait qui a perdu de l'argent.
 *
 * ⚠️ Le CHECK de {@code maintenance_details.supplied_by} (V36) tient la liste des
 * mots que la base accepte ; un mot neuf passe par une migration.
 */
public enum PartSupplier {
    /** La pièce sort du magasin de Saphir : elle se facture au client. */
    SAPHIR("Saphir"),
    /** La pièce vient du client : l'atelier la pose, il ne la vend pas. */
    CLIENT("Client");

    private final String label;

    PartSupplier(String label) {
        this.label = label;
    }

    /** Le mot que l'atelier lit à l'écran. */
    public String label() {
        return label;
    }
}
