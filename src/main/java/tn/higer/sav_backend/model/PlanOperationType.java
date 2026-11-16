package tn.higer.sav_backend.model;

/**
 * Ce que l'atelier fait à une opération du plan. Le document Higer écrit ces
 * mots en français (« remplacer », « contrôler »…), l'API les renvoie en
 * minuscules, et l'import Excel passe par {@link #fromLabel} pour admettre les
 * variantes du fichier.
 *
 * Une énumération et pas un texte libre : la fréquence d'une opération se lit
 * avec son action. « 10 000 km » ne veut rien dire sans savoir s'il faut
 * remplacer, contrôler ou graisser. Un mot inconnu est donc une erreur
 * d'import signalée, jamais une valeur recopiée telle quelle.
 */
public enum PlanOperationType {

    REPLACE("remplacer"),
    INSPECT("contrôler"),
    LUBRICATE("graisser"),
    CLEAN("nettoyer"),
    ADJUST("régler");

    private final String label;

    PlanOperationType(String label) {
        this.label = label;
    }

    /** Ce que l'écran affiche. */
    public String label() {
        return label;
    }

    /**
     * Le mot du fichier, tel qu'un humain l'a tapé. Les accents, la casse et
     * l'orthographe du document varient ; « remplace », « Remplacer » et
     * « REMPLACER » désignent la même action.
     *
     * @return null si le mot ne correspond à aucune action connue
     */
    public static PlanOperationType fromLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String needle = fold(raw);
        if (needle.isEmpty()) {
            return null;
        }
        for (PlanOperationType type : values()) {
            String label = fold(type.label());
            if (needle.equals(label) || needle.startsWith(label) || label.startsWith(needle)) {
                return type;
            }
            // L'API rend le nom de la constante en minuscules (« lubricate », « adjust ») :
            // ce que le serveur écrit doit se relire chez lui. Sans cette ligne, l'aperçu
            // d'un import se refuse à l'enregistrement — le commit renvoie la demande du
            // preview telle quelle, et « lubricate » n'est pas « graisser ».
            if (needle.equals(fold(type.name()))) {
                return type;
            }
        }
        // Le document Higer écrit « verification » sans accent et parfois
        // « check » ; ces deux synonymes reviennent sur chaque palier.
        if (needle.startsWith("verif") || needle.startsWith("check") || needle.startsWith("inspect")) {
            return INSPECT;
        }
        if (needle.startsWith("chg") || needle.startsWith("replac") || needle.startsWith("mont")) {
            return REPLACE;
        }
        if (needle.startsWith("graiss") || needle.startsWith("lube") || needle.startsWith("greas")) {
            return LUBRICATE;
        }
        if (needle.startsWith("nettoy") || needle.startsWith("clean") || needle.startsWith("wash")) {
            return CLEAN;
        }
        if (needle.startsWith("regl") || needle.startsWith("ajust") || needle.startsWith("tight")) {
            return ADJUST;
        }
        return null;
    }

    /**
     * Minuscules, sans accent ni espace ni ponctuation : ce que le fichier peut
     * bien écrire. Les chiffres restent, parce qu'ils portent le sens d'une ref
     * (« P1 » n'est pas « P2 ») et d'un palier (« 10 000 km » n'est pas
     * « 20 000 km »).
     */
    public static String fold(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase();
    }
}
