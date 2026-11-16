package tn.higer.sav_backend.model;

/**
 * Où en est une pièce demandée sur une sortie.
 *
 * Cinq états, et <b>aucun ne se saisit</b> : chacun est la conséquence d'un geste
 * écrit ailleurs (l'homme demande, le guichet commande, le magasin réceptionne, le
 * technicien pose). Un état tapé à la main serait la porte de sortie du contrôle —
 * une pièce marquée « Livrée » sans rien reçu ferait partir une deuxième sortie
 * pour rien.
 *
 * {@code PARTIELLE} n'est pas une nuance de {@code LIVREE} : c'est la commande
 * d'origine toujours ouverte, et le motif pour lequel le bus attend. Elle
 * n'ouvre donc aucune mission — seule une pièce entièrement livrée envoie quelqu'un
 * la monter.
 *
 * La liste est écrite dans la contrainte CHECK de la base (V31). Ajouter un état
 * ici sans migration redonne le 500 de V30 : le PostgreSQL refuse la ligne, les
 * tests Java ne le voient pas, et c'est l'écran du technicien qui le prend.
 */
public enum PartRequestState {
    A_COMMANDER("À commander"),
    COMMANDEE("Commandée"),
    PARTIELLE("Partielle"),
    LIVREE("Livrée"),
    POSEE("Posée");

    private final String label;

    PartRequestState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
