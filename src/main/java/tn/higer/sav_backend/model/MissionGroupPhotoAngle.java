package tn.higer.sav_backend.model;

/**
 * L'angle d'une preuve photo d'un dossier groupe.
 *
 * Le déplacement se prouve par quatre clichés : le compteur et la voiture au
 * départ, les mêmes au retour. Un chiffre tapé ne remplace pas la photo ; la
 * photo, elle, se lit sans que personne retape rien.
 *
 * Le constat d'un bus porte aussi ses deux clichés — avant et après — et reste
 * attaché à sa ligne : un dossier à deux bus ne mélange pas les preuves.
 */
public enum MissionGroupPhotoAngle {
    /** La voiture avant de quitter l'atelier. */
    DEPART("la voiture au départ"),
    /** Le compteur de la voiture au départ. */
    COMPTEUR_DEPART("le compteur au départ"),
    /** Le bus tel que l'équipe le trouve. */
    BUS_AVANT("le bus avant"),
    /** Le bus tel que l'équipe le laisse. */
    BUS_APRES("le bus après"),
    /** La voiture en rentrant à l'atelier. */
    RETOUR("la voiture au retour"),
    /** Le compteur de la voiture en rentrant à l'atelier. */
    COMPTEUR_RETOUR("le compteur au retour");

    private final String label;

    MissionGroupPhotoAngle(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Une preuve est-elle attachée à une ligne de bus ? */
    public boolean surUnBus() {
        return this == BUS_AVANT || this == BUS_APRES;
    }

    /** Le mot de l'écran, accepté en minuscules ; un angle inconnu est refusé à l'écrit. */
    public static MissionGroupPhotoAngle parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Choisissez l'angle de la photo.");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "Angle de photo inconnu (« " + raw + " ») : compteur, voiture ou bus, au départ "
                            + "et au retour, sont acceptés.");
        }
    }
}
