package tn.higer.sav_backend.model;

import java.util.List;

/**
 * Le « type » de l'atelier : deux mots, {@link #MECANIQUE} et {@link #ELECTRIQUE}.
 *
 * La famille (voir {@link PieceFamily}) dit où chercher la pièce ; le type dit de
 * quelle nature elle est, et l'atelier ne distingue que deux choses — une pièce qui
 * se monte, une pièce qui se branche. Les deux coexistent parce qu'ils ne répondent
 * pas à la même question : un vérin de frein reste du Freinage ET du Mécanique.
 *
 * Contrairement à la famille, le type se lit « à classer » quand il est vide : la
 * case n'est pas obligatoire, parce que rien ici ne s'égare sans elle. Aucun repli
 * non plus — une pièce sans type ne devient pas « Mécanique » par défaut, elle
 * s'affiche « — » et l'atelier la classe à l'écran.
 *
 * La liste est fermée en base par une contrainte CHECK ({@code V45}). Un troisième
 * mot serait une décision d'atelier, donc une migration, et le test de parité qui
 * relit {@code pg_constraint} le redirait avant qu'un classeur ne le refuse.
 */
public enum PieceType {

    MECANIQUE("Mécanique", "mecanique", "mechanical"),
    ELECTRIQUE("Électrique", "electrique", "electric", "electrical");

    private final String label;
    private final String[] spellings;

    PieceType(String label, String... spellings) {
        this.label = label;
        this.spellings = spellings;
    }

    /** Ce que l'écran et les papiers affichent. */
    public String label() {
        return label;
    }

    /** Les deux mots, dans l'ordre où l'atelier les a dits. */
    public static List<PieceType> toutes() {
        return List.of(values());
    }

    /** Les deux mots, tels qu'ils s'écrivent à l'atelier — pour un refus et pour la notice. */
    public static String mots() {
        StringBuilder words = new StringBuilder();
        for (PieceType type : toutes()) {
            if (words.length() > 0) {
                words.append(", ");
            }
            words.append(type.label());
        }
        return words.toString();
    }

    /**
     * Le type désigné par ce mot, ou {@code null} s'il n'en désigne aucun. Le nom de
     * l'enum se relit lui aussi (« ELECTRIQUE » revient du JSON tel que l'écran le
     * renvoie) ; les accents, la casse et les espaces sont pliés, parce que le
     * classeur écrit « electrique » et l'écran « Électrique ».
     */
    public static PieceType lit(String raw) {
        if (raw == null) {
            return null;
        }
        String needle = PlanOperationType.fold(raw);
        if (needle.isEmpty()) {
            return null;
        }
        PieceType found = null;
        for (PieceType type : values()) {
            boolean touche = needle.equals(PlanOperationType.fold(type.name()));
            for (String spelling : type.spellings) {
                touche = touche || needle.equals(spelling);
            }
            if (!touche) {
                continue;
            }
            if (found != null && found != type) {
                return null;
            }
            found = type;
        }
        return found;
    }
}
