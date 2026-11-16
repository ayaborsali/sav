# =============================================================================
# Image de l'API du portail SAV (Spring Boot 3.4 / Java 21)
# Construction en deux étapes : compilation Maven, puis exécution sur un JRE seul.
#
#   docker build -t sav-backend .
#
# Les secrets ne sont JAMAIS copiés dans l'image (voir .dockerignore) : ils
# arrivent au démarrage par variables d'environnement (docker-compose).
# =============================================================================

# --- Étape 1 : compilation ---------------------------------------------------
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY mvnw mvnw.cmd ./
COPY .mvn/ .mvn/

# Dépendances téléchargées une fois (but cache) : si ce goal n'est pas complet,
# l'étape « package » suivante récupère ce qui manque.
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
# Jar exécutable (repackage spring-boot-maven-plugin). application-local.properties et .env
# ne font pas partie du contexte de construction (voir .dockerignore) : l'image est propre.
RUN mvn -B -DskipTests package

# --- Étape 2 : exécution -----------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl : uniquement pour la sonde de santé du conteneur (/actuator/health).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Utilisateur dédié, sans droits particuliers.
RUN groupadd --system spring && useradd --system --gid spring --home-dir /app spring

# Le dossier des fichiers du portail (photos de sortie, pièces jointes des
# documents) est créé AVANT le changement d'utilisateur : quand Docker y monte un
# volume vide, il reprend le propriétaire du chemin dans l'image — sans cette
# ligne, le volume appartient à root et l'API ne peut plus rien écrire.
RUN mkdir -p /app/data && chown -R spring:spring /app

COPY --from=build --chown=spring:spring /build/target/*.jar app.jar

USER spring
EXPOSE 8080

# Mémoire adaptée au conteneur ; JAVA_OPTS est remplaçable au démarrage.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
