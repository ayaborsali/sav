package tn.higer.sav_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Le fil de la poussée : une notification part après l'enregistrement, jamais
 * pendant. Un envoi est un appel réseau qui peut traîner ; le mettre sur son
 * propre fil évite qu'un service de poussée lent retarde la réponse à l'atelier.
 *
 * Deux fils suffisent — les annonces sont rares — et, si la file débordait, le
 * tour passerait au fil appelant plutôt que d'être perdu : mieux vaut un
 * enregistrement un peu plus lent qu'une notification oubliée.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "pushExecutor")
    public ThreadPoolTaskExecutor pushExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("push-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
