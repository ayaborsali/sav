package tn.higer.sav_backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.repository.UserRepository;

@Component
public class UserSeeder implements CommandLineRunner {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;
    private final boolean production;

    public UserSeeder(UserRepository users,
                      PasswordEncoder encoder,
                      @Value("${app.admin.email:admin@higerbus.tn}") String adminEmail,
                      @Value("${app.admin.password:}") String adminPassword,
                      Environment environment) {
        this.users = users;
        this.encoder = encoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.production = environment.acceptsProfiles(Profiles.of("prod"));
    }

    @Override
    public void run(String... args) {
        // Si la table contient déjà des utilisateurs, on ne fait rien
        if (users.count() > 0) {
            System.out.println(">>> Table users déjà remplie, on ne fait rien.");
            return;
        }

        if (production) {
            // En production, aucun compte de démonstration à mot de passe connu :
            // le seul compte créé est l'administrateur, avec ADMIN_PASSWORD.
            if (adminPassword == null || adminPassword.isBlank()) {
                System.out.println(">>> ADMIN_PASSWORD absent : aucun compte créé. "
                        + "Renseignez ADMIN_PASSWORD puis redémarrez pour créer l'administrateur.");
                return;
            }
            creer(adminEmail, "Administrateur", Role.ADMIN, true, adminPassword);
            System.out.println(">>> Compte administrateur créé : " + adminEmail);
            return;
        }

        System.out.println(">>> Création des utilisateurs de démonstration...");
        creer("admin@higerbus.tn",         "Sonia Mansour",     Role.ADMIN,            true, "higer2026");
        creer("gestionnaire@higerbus.tn",  "Kamel Bouazizi",    Role.GESTIONNAIRE_SAV, true, "higer2026");
        creer("consultation@higerbus.tn",  "Moncef Trabelsi",   Role.CONSULTATION,     true, "higer2026");
        creer("desactive@higerbus.tn",     "Yassine Ben Salem", Role.CONSULTATION,     false, "higer2026");

        System.out.println(">>> Terminé ! " + users.count() + " utilisateurs créés (démonstration).");
    }

    private void creer(String email, String nom, Role role, boolean actif, String motDePasse) {
        AppUser u = new AppUser();
        u.setEmail(email);
        u.setName(nom);
        u.setPasswordHash(encoder.encode(motDePasse));
        u.setRole(role);
        u.setActive(actif);
        users.save(u);
        System.out.println(">>> Créé : " + email);
    }
}