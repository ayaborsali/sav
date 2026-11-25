package tn.higer.sav_backend.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Clé de signature des jetons : échec immédiat et explicite au démarrage si le secret
     * est absent ou trop court — jamais de secret par défaut dans le dépôt.
     */
    @Bean
    SecretKey jwtSecretKey(@Value("${app.jwt.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "ERREUR DE DÉMARRAGE : JWT_SECRET est absent. Renseignez la variable d'environnement "
                            + "JWT_SECRET (ou le fichier .env) avec une chaîne d'au moins 32 caractères.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "ERREUR DE DÉMARRAGE : JWT_SECRET est trop court (" + bytes.length
                            + " octets) : la signature HS256 exige au moins 32 caractères.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Le filtre anti-force-brute est exécuté dans la chaîne Spring Security (juste avant le
     * filtre qui lit le jeton) : on désactive sa déclaration automatique par le conteneur de
     * servlets pour ne pas le faire passer deux fois.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    SessionJwtConverter sessionJwtConverter,
                                    RateLimitFilter rateLimitFilter,
                                    @Value("${springdoc.api-docs.enabled:true}") boolean docsEnabled) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Limitation du débit sur login / demande de réinitialisation : les 429 partent
            // avant la lecture du jeton.
            .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> {
                auth
                    .requestMatchers("/error").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    // La PWA se sert avant toute connexion : l'écran d'accueil,
                    // le manifeste, le service worker et les bundles doivent
                    // répondre au premier appel du navigateur. Sans ces lignes,
                    // le denyAll final répond 403 au lien Render et l'icône ne
                    // peut pas s'installer.
                    .requestMatchers(HttpMethod.GET,
                            "/", "/index.html", "/login", "/dashboard",
                            "/photos", "/photos/**", "/reset-password",
                            "/manifest.webmanifest", "/ngsw.json", "/ngsw-worker.js",
                            "/safety-worker.js", "/worker-basic.min.js",
                            "/favicon.ico", "/*.js", "/*.css", "/icons/**", "/assets/**")
                        .permitAll()
                    .requestMatchers("/api/auth/login").permitAll()
                    // Mot de passe oublié : accessibles sans connexion (le lien reçu par email fait foi).
                    .requestMatchers("/api/auth/request-reset", "/api/auth/reset-password").permitAll()
                    // Sonde de santé : publique, sans détail (management.endpoint.health.show-details).
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // Sonde de la PWA : le téléphone vérifie que le serveur répond avant
                    // d'envoyer les photos gardées. Aucune donnée métier.
                    .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                    // Le reste de l'actuator (ici /actuator/info) n'est pas lisible par tous.
                    .requestMatchers("/actuator/**").hasRole("ADMIN");

                // Documentation d'API : ouverte seulement si springdoc est activé
                // (SPRINGDOC_ENABLED=false en production => tout retombe sur denyAll).
                if (docsEnabled) {
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                }

                auth
                    // Notifications poussées : chaque compte abonne SES téléphones,
                    // et le propriétaire d'une adresse est toujours le compte du
                    // jeton. Le technicien y entre — c'est lui qui doit sonner.
                    .requestMatchers("/api/push/**").authenticated()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    // Dépannage : module interne. Le rôle CONSULTATION (le regard du
                    // client) n'y entre pas, même en lecture — qui est parti, combien
                    // de temps et ce que ça coûte ne le concernent pas.
                    // Boutons d'étape du technicien : la propriété de la sortie est
                    // vérifiée plus bas, dans InterventionService (404 si ce n'est pas la sienne).
                    // Le versement d'une preuve photo est son acte à lui : c'est sur place qu'il est.
                    .requestMatchers(HttpMethod.POST,
                            "/api/interventions/*/depart", "/api/interventions/*/arrive",
                            "/api/interventions/*/diagnosis", "/api/interventions/*/finish",
                            "/api/interventions/*/leave-site", "/api/interventions/*/return",
                            "/api/interventions/*/photos", "/api/interventions/*/pieces",
                            "/api/interventions/*/checklist/*")
                        .hasAnyRole("ADMIN", "CHEF_ATELIER", "TECHNICIEN")
                    .requestMatchers(HttpMethod.POST, "/api/images/upload")
                        .hasAnyRole("ADMIN", "TECHNICIEN")
                    // La pièce demandée se rectifie par celui qui l'a vue, tant qu'elle
                    // n'est pas commandée : la garde d'appartenance et l'état sont vérifiés
                    // dans InterventionPartService, pas ici.
                    .requestMatchers(HttpMethod.PUT, "/api/intervention-pieces/*")
                        .hasAnyRole("ADMIN", "CHEF_ATELIER", "TECHNICIEN")
                    // Les deux gestes d'après la demande : passer la commande au
                    // guichet, réceptionner au magasin. Le compte de technicien en est
                    // exclu — il demande la pièce devant le bus, il n'écrit pas qu'elle
                    // est commandée ni arrivée. L'état qui en sort se calcule au
                    // service, jamais depuis la requête.
                    .requestMatchers(HttpMethod.POST,
                            "/api/intervention-pieces/*/commander",
                            "/api/intervention-pieces/*/receptionner")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER")
                    .requestMatchers(HttpMethod.GET,
                            "/api/interventions/**", "/api/technicians/**", "/api/service-vehicles/**",
                            "/api/intervention-photos/**")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER", "TECHNICIEN")
                    .requestMatchers("/api/interventions/**", "/api/technicians/**",
                            "/api/service-vehicles/**", "/api/intervention-photos/**")
                        .hasAnyRole("ADMIN", "CHEF_ATELIER")
                    // Missions : la demande du client et la réponse qu'on y fait. Le
                    // compte de technicien ne lit pas la file du guichet — il reçoit ce
                    // qui est décidé dehors. Le rôle CONSULTATION n'y entre pas.
                    .requestMatchers(HttpMethod.GET, "/api/missions/**")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER")
                    // Noter une demande : le guichet et l'atelier.
                    .requestMatchers(HttpMethod.POST, "/api/missions")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER")
                    // Trancher — envoyer un homme, ouvrir l'ordre, refuser, reporter,
                    // reprendre : les mêmes que pour une sortie.
                    .requestMatchers("/api/missions/**")
                        .hasAnyRole("ADMIN", "CHEF_ATELIER")
                    // Mission groupe : le registre des déplacements de l'atelier.
                    // Le technicien y entre aussi — il lit ses propres dossiers et
                    // pointe le départ et le retour ; le service limite chaque geste
                    // au dossier où il est l'homme affecté.
                    .requestMatchers(HttpMethod.GET,
                            "/api/missions-groupe/**", "/api/mission-group-photos/**")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER", "TECHNICIEN")
                    .requestMatchers("/api/missions-groupe/**", "/api/mission-group-photos/**")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER", "TECHNICIEN")
                    // Les papiers de l'atelier. La fiche d'un ordre — ses origines, les
                    // photos de la sortie dont il vient, les heures de l'homme — se lit du
                    // guichet et de l'atelier, pas du regard du client : il n'a rien à faire
                    // des preuves internes ni du temps passé. Le PDF sorti de cette fiche a
                    // les mêmes lecteurs qu'elle : une feuille signable que n'importe quel
                    // compte connecté pourrait télécharger serait la fiche ouverte par un
                    // autre chemin.
                    .requestMatchers(HttpMethod.GET, "/api/maintenance/*/fiche", "/api/maintenance/*/cloture",
                            "/api/maintenance/*/document.pdf")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER")
                    // Le geste de l'atelier : écrire sur un papier qui existe (cocher une
                    // opération, poser les heures réelles, écrire l'anomalie), clôturer
                    // l'ordre, rendre le bus, et signer la restitution. Créer un papier et
                    // parler au client restent au guichet.
                    .requestMatchers(HttpMethod.PUT, "/api/maintenance/*")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER")
                    .requestMatchers(HttpMethod.POST, "/api/maintenance/*/cloturer",
                            "/api/maintenance/*/bon-livraison", "/api/maintenance/*/signature")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER")
                    // Tout utilisateur connecté (même « consultation ») peut se déconnecter.
                    .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                    // Tout utilisateur connecté (même « consultation ») peut changer son propre mot de passe.
                    .requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()
                    // Les fiches du portefeuille — parc de bus et annuaire des clients —
                    // se lisent du guichet et de l'atelier. Le technicien sort sur une
                    // affectation, le bus lui est donné ; le rôle CONSULTATION est le
                    // regard d'UN client sur SES dossiers, pas l'inventaire de Saphir.
                    // Sans cette ligne, la règle générique du GET laissait les deux
                    // listes ouvertes à n'importe quel compte connecté.
                    .requestMatchers(HttpMethod.GET, "/api/vehicles/**", "/api/clients/**")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER")
                    // L'atelier peut créer le client absent au moment de préparer une mission,
                    // sans recevoir pour autant le droit de modifier ou supprimer la fiche.
                    .requestMatchers(HttpMethod.POST, "/api/clients")
                        .hasAnyRole("ADMIN", "GESTIONNAIRE_SAV", "CHEF_ATELIER")
                    .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                    .requestMatchers("/api/**").hasAnyRole("ADMIN", "GESTIONNAIRE_SAV")
                    .anyRequest().denyAll();
            })
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(sessionJwtConverter)));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origin}") String origin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}