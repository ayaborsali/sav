package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.TechnicianDtos.TechnicianLookup;
import tn.higer.sav_backend.dto.TechnicianDtos.TechnicianRequest;
import tn.higer.sav_backend.dto.TechnicianDtos.TechnicianResponse;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.UserRepository;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.TechnicianDirectory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fiches techniciens. Les lire est réservé aux comptes internes du dépannage
 * (ADMIN, CHEF_ATELIER, GESTIONNAIRE_SAV) : un compte TECHNICIEN passe par sa
 * propre fiche, et la consultation n'entre pas dans ce module.
 *
 * Le taux horaire et le forfait de sortie ne partent qu'à ADMIN et CHEF_ATELIER
 * ({@link Access#seesPayroll}) : une fiche sans ces deux colonnes reste utile au
 * technicien, qui doit savoir quelle sortie est la sienne.
 */
@RestController
@RequestMapping("/api/technicians")
public class TechnicianController {

    private final TechnicianRepository technicians;
    private final UserRepository users;
    private final TechnicianDirectory directory;
    private final AuditService audit;

    public TechnicianController(TechnicianRepository technicians, UserRepository users,
                                TechnicianDirectory directory, AuditService audit) {
        this.technicians = technicians;
        this.users = users;
        this.directory = directory;
        this.audit = audit;
    }

    @GetMapping
    public List<TechnicianResponse> list(Authentication auth) {
        requireInternal(auth);
        boolean payroll = Access.seesPayroll(auth);
        List<Technician> rows = technicians.findAllByOrderByActiveDescNameAsc();
        // Un seul aller-retour pour les e-mails reliés : la liste affiche « relié à qui ».
        List<UUID> ids = rows.stream().map(Technician::getUserId).filter(Objects::nonNull).toList();
        Map<UUID, String> emails = ids.isEmpty() ? Map.of()
                : users.findAllById(ids).stream()
                        .collect(Collectors.toMap(AppUser::getId, AppUser::getEmail));
        return rows.stream()
                .map(t -> TechnicianResponse.from(t, payroll,
                        t.getUserId() != null ? emails.get(t.getUserId()) : null))
                .toList();
    }

    /**
     * La liste des gens à envoyer en sortie ou à rattacher à un dossier, prise dans
     * la table des comptes. Les fiches de {@link #list} restent le dossier de paie :
     * ce endpoint-ci répond même quand aucune fiche n'a encore été ouverte, sinon
     * l'atelier devrait créer la fiche avant de pouvoir choisir la personne.
     *
     * Aucun montant n'y figure — la liste est faite pour être ouverte devant un
     * écran, la paie se lit sur la fiche. La lecture reste interne (règle SecurityConfig).
     */
    @GetMapping("/lookup")
    public List<TechnicianLookup> lookup() {
        return directory.options();
    }

    @GetMapping("/{id}")
    public TechnicianResponse get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        requireInternal(auth);
        Technician t = find(id);
        if (Access.isTechnician(auth) && !isSelf(t, jwt)) {
            // Un technicien qui demande la fiche d'un autre ne doit pas savoir qu'elle existe.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Technicien introuvable.");
        }
        return TechnicianResponse.from(t, Access.seesPayroll(auth), emailOf(t.getUserId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TechnicianResponse create(@Valid @RequestBody TechnicianRequest request,
                                     @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        requireManager(auth);
        if (technicians.existsByNameIgnoreCase(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une fiche technicien porte déjà ce nom.");
        }
        Technician t = new Technician();
        t.setId("tch-" + UUID.randomUUID());
        apply(t, request);
        technicians.save(t);
        audit.record("CREATE", "TECHNICIAN", t.getId(), "Création de la fiche technicien " + t.getName(), jwt);
        return TechnicianResponse.from(t, Access.seesPayroll(auth), emailOf(t.getUserId()));
    }

    @PutMapping("/{id}")
    public TechnicianResponse update(@PathVariable String id, @Valid @RequestBody TechnicianRequest request,
                                     @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        requireManager(auth);
        Technician t = find(id);
        if (technicians.existsByNameIgnoreCaseAndIdNot(request.name(), id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une autre fiche technicien porte déjà ce nom.");
        }
        apply(t, request);
        technicians.save(t);
        audit.record("UPDATE", "TECHNICIAN", id, "Mise à jour de la fiche technicien " + t.getName(), jwt);
        return TechnicianResponse.from(t, Access.seesPayroll(auth), emailOf(t.getUserId()));
    }

    /**
     * Retirer une fiche de la liste n'efface rien : les sorties passées gardent
     * son nom, et un technicien parti reste comptable des primes touchées.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        requireManager(auth);
        Technician t = find(id);
        t.setActive(false);
        technicians.save(t);
        audit.record("DELETE", "TECHNICIAN", id,
                "Fiche technicien « " + t.getName() + " » retirée de la liste (conservée pour l'historique)", jwt);
    }

    private void apply(Technician t, TechnicianRequest r) {
        t.setName(r.name().trim());
        t.setPhone(blankToNull(r.phone()));
        t.setHourlyRate(r.hourlyRate());
        t.setDispatchAllowance(r.dispatchAllowance());
        t.setNotes(blankToNull(r.notes()));
        if (r.active() != null) {
            t.setActive(r.active());
        }
        if (r.userId() != null) {
            AppUser u = users.findById(r.userId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Le compte indiqué n'existe pas : créez-le d'abord dans Utilisateurs."));
            link(t, u);
        } else if (notBlank(r.userEmail())) {
            String email = r.userEmail().trim();
            AppUser u = users.findByEmailIgnoreCase(email)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Aucun compte du portail ne porte l'e-mail « " + email
                                    + " ». Créez-le d'abord dans Administration › Utilisateurs, avec le rôle technicien."));
            link(t, u);
        }
    }

    /** Un compte = un technicien, et ce compte doit avoir le rôle technicien. */
    private void link(Technician t, AppUser u) {
        if (u.getRole() != Role.TECHNICIEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le compte « " + u.getEmail() + " » n'a pas le rôle technicien "
                            + "(il a « " + u.getRole().name().toLowerCase() + " »).");
        }
        technicians.findByUserId(u.getId()).ifPresent(other -> {
            if (!other.getId().equals(t.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le compte « " + u.getEmail() + " » est déjà relié à la fiche "
                                + other.getName() + " : un compte par technicien.");
            }
        });
        t.setUserId(u.getId());
    }

    private String emailOf(UUID userId) {
        if (userId == null) return null;
        return users.findById(userId).map(AppUser::getEmail).orElse(null);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private Technician find(String id) {
        return technicians.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Technicien introuvable."));
    }

    private static boolean isSelf(Technician t, Jwt jwt) {
        return t.getUserId() != null && jwt != null && jwt.getSubject() != null
                && t.getUserId().toString().equals(jwt.getSubject());
    }

    /** Lecture interne : la consultation, qui voit le portail côté client, n'entre pas ici. */
    private void requireInternal(Authentication auth) {
        if (!Access.usesDepannageModule(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Le module dépannage est interne : votre compte n'y a pas accès.");
        }
    }

    private void requireManager(Authentication auth) {
        if (!Access.managesInterventions(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Seuls le chef d'atelier et l'administrateur gèrent les fiches techniciens.");
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
