package tn.higer.sav_backend.model;

/**
 * Où en est une commande de pièces, sur les quatre états du papier de référence.
 *
 * {@code PARTIELLE} n'est pas une nuance de {@code LIVREE} : c'est la commande
 * d'origine toujours ouverte, et le motif pour lequel un bus attend. Le serveur
 * refuse d'ailleurs « livrée » avec moins reçu que commandé.
 */
public enum PartOrderState {
    EN_COURS("En cours"),
    LIVREE("Livrée"),
    PARTIELLE("Partielle"),
    ANNULEE("Annulée");

    private final String label;

    PartOrderState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
