package tn.higer.sav_backend.model;

import java.util.List;

/**
 * Les familles du catalogue de pièces de rechange — le « type PR » de l'atelier.
 *
 * Une pièce se range dans UNE famille, et c'est une propriété de la pièce : un
 * amortisseur reste de la suspension qu'on le monte sur un bus sous garantie ou
 * hors contrat. Ce n'est ni un état ni une étape, et rien ne le recalcule :
 * l'atelier le choisit à l'entrée au catalogue.
 *
 * La liste est fermée, et c'est un choix de l'atelier (« mecanique / suspension /
 * electricite / freinage / boite de vitesse / pont arriere / divers »), pas une
 * déduction du portail. Elle est verrouillée en base par une contrainte CHECK : si
 * un huitième mot arrive un jour — carrosserie, climatisation, pneus — ce sera une
 * migration, et le test de parité qui relit {@code pg_constraint} le redit avant
 * qu'un premier classeur ne le refuse.
 *
 * {@link #lit} accepte les accents, la casse, les espaces et les traductions
 * directes (« Brake », « Gearbox », « Rear axle ») : un document Higer écrit
 * « FREINAGE », le tableur d'un collègue « freinage », et les deux disent la même
 * chose. Ce qui ne désigne aucune famille renvoie {@code null}, et le service
 * refuse en nommant les sept mots. Le repli sur « Divers » n'existe pas : « Divers »
 * se choisit, cela ne se déduit pas d'un mot qu'on n'a pas reconnu — y ranger une
 * pièce par défaut lui collerait une étiquette que personne n'a mise, dans un champ
 * qui sert justement à retrouver ce qu'on cherche.
 */
public enum PieceFamily {

    MECANIQUE("Mécanique", "mecanique", "mechanical"),
    SUSPENSION("Suspension", "suspension"),
    ELECTRICITE("Électricité", "electricite", "electric", "electrical"),
    FREINAGE("Freinage", "freinage", "brake", "braking"),
    // Le « de » du mot français compte : « Boîte de vitesse » se plie en
    // « boitedevitesse », et une orthographe qui ne se lit pas irait refuser la ligne
    // d'un classeur parfaitement clair.
    BOITE_VITESSE("Boîte de vitesse", "boitedevitesse", "boitevitesse", "gearbox"),
    PONT_ARRIERE("Pont arrière", "pontarriere", "rearaxle"),
    DIVERS("Divers", "divers", "autre", "autres", "misc");

    private final String label;
    private final String[] spellings;

    PieceFamily(String label, String... spellings) {
        this.label = label;
        this.spellings = spellings;
    }

    /** Ce que l'écran et les papiers affichent. */
    public String label() {
        return label;
    }

    /** Les sept familles, dans l'ordre où l'atelier les a dites — pour un select et pour un refus. */
    public static List<PieceFamily> toutes() {
        return List.of(values());
    }

    /** Les sept mots, tels qu'ils s'écrivent à l'atelier. Sert aux messages de refus et à la notice. */
    public static String mots() {
        StringBuilder words = new StringBuilder();
        for (PieceFamily family : toutes()) {
            if (words.length() > 0) {
                words.append(", ");
            }
            words.append(family.label());
        }
        return words.toString();
    }

    /**
     * La famille désignée par ce mot, ou {@code null} s'il n'en désigne aucune — y
     * compris s'il en désigne deux, parce qu'un mot ambigu ne s'arbitre pas ici.
     *
     * Le nom de l'enum se relit lui aussi (« BOITE_VITESSE » revient de l'écran et
     * du JSON, « boîte de vitesse » vient du classeur) : une valeur que le serveur a
     * rendue doit pouvoir lui être renvoyée telle quelle.
     */
    public static PieceFamily lit(String raw) {
        if (raw == null) {
            return null;
        }
        String needle = PlanOperationType.fold(raw);
        if (needle.isEmpty()) {
            return null;
        }
        PieceFamily found = null;
        for (PieceFamily family : values()) {
            if (!matches(family, needle)) {
                continue;
            }
            if (found != null && found != family) {
                return null;
            }
            found = family;
        }
        return found;
    }

    private static boolean matches(PieceFamily family, String needle) {
        if (needle.equals(PlanOperationType.fold(family.name()))) {
            return true;
        }
        for (String spelling : family.spellings) {
            if (needle.equals(spelling)) {
                return true;
            }
        }
        return false;
    }
}
