package tn.higer.sav_backend.model;

/**
 * La seconde vie d'un dossier garantie : ce que le constructeur en a fait.
 *
 * Elle est tenue À PART du statut du dossier, parce que les deux ne sont pas
 * décidés par les mêmes mains. « Approuvé » et « Rejeté », dans
 * {@link SavCaseStatus}, sont posés par un humain de chez Saphir. Ici c'est le
 * constructeur qui parle — et Saphir ne peut pas écrire à sa place une décision
 * qui engage son remboursement.
 *
 * Le parcours n'est pas linéaire au sens strict : un claim accordé peut être
 * contesté parce que le montant accordé est plus bas que le montant demandé.
 * {@link #avanceDepuis} borne donc ce qui est licite, et refuse tout retour en
 * arrière — un claim « réglé » ne se redépose pas.
 */
public enum ClaimState {
    A_DEPOSER("À déposer"),
    DEPOSE("Déposé"),
    ACCORDE("Accordé"),
    REFUSE("Refusé"),
    CONTESTE("Contesté"),
    REGLE("Réglé");

    private final String label;

    ClaimState(String label) {
        this.label = label;
    }

    /** Le mot que l'atelier lit à l'écran. */
    public String label() {
        return label;
    }

    /**
     * Le passage de {@code de} à {@code vers} est-il un pas en avant ?
     *
     * Refusé et Accordé peuvent se contester — c'est la suite normale quand le
     * constructeur tranche mal. Réglé est terminal : l'argent est reçu (ou l'avoir
     * est pris), et y revenir écrirait un faux dans un papier de remboursement.
     */
    public boolean avanceDepuis(ClaimState de) {
        if (de == null || de == this) {
            return true;
        }
        return switch (de) {
            case A_DEPOSER -> this != REGLE;
            case DEPOSE -> this == ACCORDE || this == REFUSE || this == CONTESTE;
            case ACCORDE -> this == CONTESTE || this == REGLE;
            case REFUSE -> this == CONTESTE;
            case CONTESTE -> this == ACCORDE || this == REFUSE || this == REGLE;
            case REGLE -> false;
        };
    }

    /** Refusé ou contesté : le constructeur a dit non, et ça s'écrit avec un motif. */
    public boolean exigeUnMotif() {
        return this == REFUSE || this == CONTESTE;
    }
}
