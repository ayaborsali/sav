package tn.higer.sav_backend.model;

/**
 * D'où vient une ligne du journal d'une sortie.
 *
 * SERVER  : l'heure a été posée par le serveur quand un bouton de l'application
 *           a été pressé. C'est la valeur de contrôle.
 * MANAGED : la valeur a été entrée à la main par un compte interne (une
 *           correction, une note). Elle est traçable, jamais cachée.
 * TELEMETRY : réservé au capteur GPS de la voiture de déplacement. Vide aujourd'hui :
 *           écrire TELEMETRY sans capteur installé serait inscrire une fausse
 *           preuve.
 */
public enum InterventionEventSource {
    SERVER,
    MANAGED,
    TELEMETRY
}
