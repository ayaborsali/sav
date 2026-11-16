package tn.higer.sav_backend.model;

/** D'où vient l'heure « prise » d'une photo — la nuance est le contrôle. */
public enum PhotoTimeSource {
    /** Lue dans les métadonnées EXIF du fichier : l'heure de l'appareil photo. */
    EXIF,
    /** Fichier sans EXIF : on ne connaît que l'heure du versement sur le poste. */
    UPLOAD
}
