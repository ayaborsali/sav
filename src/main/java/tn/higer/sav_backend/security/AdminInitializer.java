package tn.higer.sav_backend.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.repository.UserRepository;

@Slf4j
@Component
public class AdminInitializer implements CommandLineRunner {

    /** Mot de passe des comptes de test documentés dans le README du frontend. */
    private static final String DEMO_PASSWORD = "higer2026";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminInitializer(UserRepository users, PasswordEncoder encoder,
                            @Value("${app.admin.email}") String adminEmail,
                            @Value("${app.admin.password}") String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (users.count() > 0) return;

        seed(adminEmail, "Sonia Mansour", Role.ADMIN, adminOrDefault(), true);
        seed("gestionnaire@higerbus.tn", "Kamel Bouazizi", Role.GESTIONNAIRE_SAV, DEMO_PASSWORD, true);
        seed("consultation@higerbus.tn", "Moncef Trabelsi", Role.CONSULTATION, DEMO_PASSWORD, true);
        seed("desactive@higerbus.tn", "Yassine Ben Salem", Role.CONSULTATION, DEMO_PASSWORD, false);
        log.info("Comptes initiaux créés. Mot de passe des comptes de test : {}", DEMO_PASSWORD);
    }

    private String adminOrDefault() {
        return (adminPassword == null || adminPassword.isBlank()) ? DEMO_PASSWORD : adminPassword;
    }

    private void seed(String email, String name, Role role, String password, boolean active) {
        AppUser user = new AppUser();
        user.setName(name);
        user.setEmail(email.trim());
        user.setPasswordHash(encoder.encode(password));
        user.setRole(role);
        user.setActive(active);
        users.save(user);
        log.info("Compte créé : {} ({}) — actif : {}", email, role, active);
    }
}
