package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.TechnicianDtos.TechnicianLookup;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Les gens, avant les fiches.
 *
 * Un technicien est d'abord un compte du portail : quelqu'un qui se connecte, dont
 * on peut donc dater les boutons. La fiche `technicians` n'est que son dossier de
 * paie (taux horaire, forfait de sortie). Avant ce service, l'atelier devait créer
 * les deux séparément, et l'écran de saisie offrait un champ de texte libre : le
 * dossier SAV se remplissait d'un nom tapé à la main, sans lien avec la personne
 * envoyée en sortie.
 *
 * Ici, choisir une personne suffit : si son dossier de paie manque, il est créé
 * sans taux, et la prime se signale d'elle-même comme non calculable. Rien n'est
 * inventé à la place d'un chiffre d'argent que Saphir n'a pas encore donné.
 */
@Service
public class TechnicianDirectory {

    private final TechnicianRepository technicians;
    private final UserRepository users;
    private final AuditService audit;

    public TechnicianDirectory(TechnicianRepository technicians, UserRepository users, AuditService audit) {
        this.technicians = technicians;
        this.users = users;
        this.audit = audit;
    }

    /**
     * La liste de choix : les comptes techniciens actifs, dans l'ordre des noms.
     * Aucune valeur de paie ne sort d'ici.
     */
    @Transactional(readOnly = true)
    public List<TechnicianLookup> options() {
        List<AppUser> accounts = users.findByRoleAndActiveTrueOrderByNameAsc(Role.TECHNICIEN);
        if (accounts.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> fichesByAccount = technicians
                .findByUserIdIn(accounts.stream().map(AppUser::getId).toList()).stream()
                .filter(t -> t.getUserId() != null)
                .collect(Collectors.toMap(Technician::getUserId, Technician::getId,
                        (first, other) -> first));
        return accounts.stream()
                .map(u -> new TechnicianLookup(u.getId().toString(), u.getName(), u.getEmail(),
                        fichesByAccount.get(u.getId())))
                .toList();
    }

    /**
     * Le dossier de paie de ce compte, créé au besoin.
     *
     * Trois cas, dans cet ordre : la fiche existe déjà et est reliée ; elle existe
     * sous le même nom sans compte relié (une fiche ouverte à la main avant ce
     * chantier) et on la rattache au lieu de doublonner ; sinon elle est créée,
     * taux et forfait laissés vides.
     */
    @Transactional
    public Technician ficheFor(UUID userId, Jwt jwt) {
        AppUser account = requireAccount(userId);
        return technicians.findByUserId(account.getId())
                .orElseGet(() -> linkOldFicheOrNew(account, jwt));
    }

    /**
     * Le compte choisi doit exister, être technicien et être actif. Un chef
     * d'atelier qui envoie un compte admin en sortie écrirait une prime au nom de
     * quelqu'un qui n'existe pas sur le terrain : le refus est écrit, pas silencieux.
     */
    public AppUser requireAccount(UUID userId) {
        AppUser account = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce technicien n'existe plus dans le portail : choisissez-le dans la liste, "
                                + "et faites créer le compte par l'administrateur s'il manque."));
        if (account.getRole() != Role.TECHNICIEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le compte « " + account.getEmail() + " » n'est pas un compte technicien "
                            + "(il a le rôle « " + account.getRole().name().toLowerCase()
                            + " »). Seuls les comptes technicien sortent en déplacement.");
        }
        if (!account.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le compte « " + account.getEmail() + " » est désactivé : "
                            + "il ne peut plus être affecté à une sortie ni à un dossier.");
        }
        return account;
    }

    private Technician linkOldFicheOrNew(AppUser account, Jwt jwt) {
        Technician old = technicians.findByNameIgnoreCase(account.getName()).orElse(null);
        if (old != null && old.getUserId() == null) {
            old.setUserId(account.getId());
            technicians.save(old);
            audit.record("UPDATE", "TECHNICIAN", old.getId(),
                    "Fiche technicien « " + old.getName() + " » reliée au compte " + account.getEmail(), jwt);
            return old;
        }
        Technician t = new Technician();
        t.setId("tch-" + UUID.randomUUID());
        t.setName(account.getName());
        t.setUserId(account.getId());
        t.setActive(true);
        technicians.save(t);
        audit.record("CREATE", "TECHNICIAN", t.getId(),
                "Fiche technicien créée automatiquement pour le compte " + account.getEmail()
                        + " (taux horaire et forfait encore à saisir par le chef d'atelier)", jwt);
        return t;
    }
}
