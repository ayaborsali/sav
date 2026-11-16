package tn.higer.sav_backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        String message = e.getReason() != null ? e.getReason() : "Erreur";
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> switch (baseName(error.getField())) {
                    case "email" -> "Veuillez saisir une adresse email valide.";
                    case "password" -> "Le mot de passe doit contenir entre 8 et 72 caractères.";
                    case "token" -> "Lien de réinitialisation invalide.";
                    case "vin" -> "Le N° de châssis (VIN) est obligatoire.";
                    case "registration" -> "L'immatriculation est obligatoire.";
                    case "clientId" -> "Le client exploitant est obligatoire.";
                    case "contractDate" -> "La date du contrat est obligatoire.";
                    case "unitPriceHt" -> "Le prix unitaire HT doit être un montant positif, au plus 2 décimales.";
                    case "vehicleId" -> "Chaque ligne de contrat doit référencer un véhicule.";
                    // Un champ non listé porte déjà sa phrase, écrite sur son annotation
                    // dans le DTO : la redire ici ferait deux sources à tenir d'accord.
                    default -> texteDe(error);
                })
                .orElse("Requête invalide.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
    }

    /** Le message posé sur l'annotation, sinon le champ nommé — « Requête invalide » seul ne disait rien. */
    private static String texteDe(org.springframework.validation.FieldError error) {
        String texte = error.getDefaultMessage();
        return texte == null || texte.isBlank()
                ? "Requête invalide : champ « " + error.getField() + " »."
                : texte;
    }

    /** « lines[0].unitPriceHt » -> « unitPriceHt » : les champs imbriqués parlent au même message. */
    private static String baseName(String field) {
        return field.replaceAll("\\[\\d+]", "").replaceFirst("^\\.", "").replaceFirst("\\.$", "");
    }
}