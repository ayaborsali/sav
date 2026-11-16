package tn.higer.sav_backend.model;

/**
 * État d'un palier dans un forfait vendu : à faire, ou fait.
 *
 * Le suivi d'une grille Higer ne se fait qu'avec ces deux états. « En cours »
 * n'existe pas : soit le bus n'est pas encore passé, soit le document qui atteste
 * le passage existe — et c'est ce document (phase C) qui bascule le palier.
 */
public enum SoldPlanStepStatus {
    TODO,
    DONE
}
