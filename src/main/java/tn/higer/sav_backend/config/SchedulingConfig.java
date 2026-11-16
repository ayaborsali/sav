package tn.higer.sav_backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Le rythme du suivi dans le temps est activé ici.
 *
 * Le Scheduler est ouvert, et chaque règle décide elle-même si son tour
 * automatique tourne : {@code app.depannage.alert-scan-enabled} pour les alertes de
 * dépannage, {@code app.forfaits.alert-scan-enabled} pour le rappel des paliers.
 *
 * Ce n'était pas le cas avant le rappel des paliers : la coupure était portée par
 * {@code @ConditionalOnProperty} sur cette classe, donc couper une règle coupait le
 * scheduler de toutes. Ouvrir le rappel des paliers aurait ainsi réveillé le scan
 * dépannage que l'atelier a éteint, sans que personne n'ait redemandé quoi que ce
 * soit — deux règles qui se réveillent l'une l'autre est exactement ce qu'on veut
 * éviter.
 *
 * Les scans restent déclenchables à la main (POST {@code /api/interventions/alerts/scan},
 * POST {@code /api/sold-plan-alerts/scan}) même tour automatique coupé : c'est la
 * même règle, appliquée au moment où l'atelier la lit.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
