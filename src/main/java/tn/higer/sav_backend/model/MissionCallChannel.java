package tn.higer.sav_backend.model;

/**
 * Par où la demande du client est arrivée.
 *
 * Le canal ne change pas le travail ; il dit comment joindre l'expéditeur et
 * comment la demande a été reçue. Un « sur place » ne rappelle personne, un
 * message WhatsApp se relit dans la conversation. Le guichet le note au moment
 * du papier, comme pour une réclamation SAV.
 */
public enum MissionCallChannel {
    TELEPHONE("Téléphone"),
    WHATSAPP("WhatsApp"),
    EMAIL("Email"),
    SUR_PLACE("Sur place"),
    AUTRE("Autre");

    private final String label;

    MissionCallChannel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Le mot de l'écran, accepté en minuscules ; un canal inconnu est refusé à l'écrit. */
    public static MissionCallChannel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "Canal d'appel inconnu (« " + raw + " ») : téléphone, WhatsApp, email, "
                            + "sur place ou autre sont acceptés.");
        }
    }
}
