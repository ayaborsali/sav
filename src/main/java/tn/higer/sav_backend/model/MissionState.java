package tn.higer.sav_backend.model;

/**
 * Où en est une mission. Quatre états écrits ici, un cinquième lu derrière :
 * « Soldée » n'est pas une case à cocher, c'est le papier du bout de la chaîne
 * (la sortie clôturée, l'ordre terminé) qui la rend vraie.
 *
 * {@code REFUSEE} et {@code REPORTEE} exigent une raison écrite. Un AG refusé est
 * une perte pour Saphir ; une demande refusée sans motif ne se justifie plus
 * devant le client six mois plus tard.
 */
public enum MissionState {
    A_TRAITER("À traiter"),
    EN_COURS("En cours"),
    SOLDEE("Soldée"),
    REFUSEE("Refusée"),
    REPORTEE("Reportée");

    private final String label;

    MissionState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
