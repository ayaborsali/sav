package tn.higer.sav_backend.model;

/**
 * L'état d'une facture, tel que le serveur le calcule — jamais tel qu'un poste le tape.
 *
 * Cet enum n'est PAS une colonne : aucune migration ne le stocke, et c'est voulu.
 * L'état se déduit de trois faits que la facture porte ou que ses encaissements
 * écrivent : la somme rentrée, le {@code totalTTC} et l'échéance {@code dueOn}.
 * {@code FactureService} le recalcule à chaque lecture et à chaque encaissement, pour
 * qu'une facture qui vieillit passe « impayée » sans que personne n'ait à y penser.
 *
 * Le partage est sans chevauchement, dans cet ordre :
 *   — {@link #PAYEE}     : tout le TTC est rentré ;
 *   — {@link #PARTIELLE} : une partie seulement est rentrée (même si l'échéance est
 *     dépassée — le versement partiel est un fait, le retard se lit à côté) ;
 *   — {@link #IMPAYEE}   : l'échéance est dépassée et rien n'est rentré ;
 *   — {@link #EMISE}     : émise, pas échue, rien rentré encore.
 *
 * Un drapeau « en retard » accompagne l'état dans la réponse : une facture partielle
 * et échue reste « partielle », mais l'écran doit pouvoir la colorer comme échue.
 */
public enum FactureStatus {
    EMISE("Émise"),
    PARTIELLE("Partielle"),
    PAYEE("Payée"),
    IMPAYEE("Impayée");

    private final String label;

    FactureStatus(String label) {
        this.label = label;
    }

    /** Le mot que le guichet lit à l'écran. */
    public String label() {
        return label;
    }
}