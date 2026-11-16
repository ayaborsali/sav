package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Allers-retours des alertes de dépannage (niveau 3 : le suivi dans le temps).
 *
 * Une alerte se lit et se lève, elle ne se saisit pas : c'est le serveur qui la
 * déclenche quand une règle annoncée à l'avance se vérifie sur une sortie. Le
 * seul champ qu'un humain écrit est la raison qui la lève.
 *
 * {@link AlertListResponse#rules()} énonce les seuils appliqués, comme la règle
 * de calcul de la prime : une règle cachée se discute, une règle écrite se
 * contrôle.
 */
public final class AlertDtos {

    private AlertDtos() {}

    /**
     * Une alerte. Le numéro de la sortie et le nom du technicien sont joints pour
     * que l'atelier n'ait pas à rouvrir chaque fiche pour savoir de qui il s'agit.
     */
    public record AlertDto(String id, String interventionId, String interventionNumber,
                           String technicianName, String kind, String kindLabel, String message,
                           LocalDateTime detectedAt, boolean waived, String waiverReason,
                           String waivedByEmail, LocalDateTime waivedAt) {}

    /** Liste des alertes encore ouvertes, avec les seuils qui les ont fait naître. */
    public record AlertListResponse(List<AlertDto> alerts, long openCount, String rules,
                                    LocalDateTime referenceTime) {}

    /** Lever une alerte : la raison est exigée, sinon l'alerte reste ouverte. */
    public record WaiveRequest(@Size(max = 500) String reason) {}

    /**
     * Résultat d'une passe demandée depuis l'écran : ce que la vérification a
     * ouvert, et la liste telle qu'elle est maintenant, pour que le poste n'ait
     * pas à relancer une deuxième requête sur des données déjà toutes faites.
     */
    public record ScanResponse(int opened, AlertListResponse alerts) {}
}
