package tn.higer.sav_backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Personnalisation de la documentation générée par springdoc
 * (Swagger UI : {@code /swagger-ui.html}, description machine : {@code /v3/api-docs}).
 *
 * <p>Le portail est protégé par jeton JWT : le bouton « Authorize » attend la valeur brute
 * du jeton renvoyé par {@code POST /api/auth/login} (sans préfixe « Bearer »).</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "jetonJWT";

    @Bean
    OpenAPI savApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("API du portail SAV — Saphir Véhicules et Automobiles")
                        .description("Dossiers SAV, entretien, véhicules, clients, contrats de maintenance et documents. "
                                + "Accès réservé aux rôles ADMIN et GESTIONNAIRE_SAV pour les écritures ; "
                                + "les montants ne sortent jamais vers le rôle CONSULTATION.")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
