package tn.higer.sav_backend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SavBackendApplicationTests {

	// Test d'intégration complet (démarrage du contexte Spring + Flyway + PostgreSQL) : exécuté uniquement là où PostgreSQL et les variables d'environnement sont fournis, c'est-à-dire le workflow CI qui monte un service postgres.
	@BeforeAll
	static void exigerUnEnvironnementDeTestIntegre() {
		Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
				"Skippé localement : le contexte a besoin de PostgreSQL et des variables d'environnement (CI uniquement).");
	}

	@Test
	void contextLoads() {
	}

}
