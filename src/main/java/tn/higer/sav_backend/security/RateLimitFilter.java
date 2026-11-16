package tn.higer.sav_backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Protection contre la force brute sur les deux portes d'entrée publiques du portail :
 * {@code POST /api/auth/login} et {@code POST /api/auth/request-reset}.
 *
 * <p>Deux garde-fous, sans dépendance externe, en mémoire uniquement :</p>
 * <ol>
 *   <li><b>Fenêtre glissante par IP</b> : au maximum N requêtes sur la même adresse
 *       pendant la fenêtre (10 connexions/minute, 5 demandes de réinitialisation/minute
 *       par défaut). Au-delà → {@code 429 Too Many Requests}.</li>
 *   <li><b>Verrouillage temporaire</b> : après N identifications échouées (401) depuis
 *       la même IP, l'IP est bloquée pendant M minutes (5 échecs → 15 minutes par défaut),
 *       y compris si les tentatives étaient espacées. Un succès efface le compteur.</li>
 * </ol>
 *
 * <p>L'adresse cliente est lue avec {@code getRemoteAddr()} : derrière le proxy nginx du
 * docker-compose, {@code server.forward-headers-strategy} (valeur {@code framework}) charge
 * Spring de remplacer l'IP du socket par celle de {@code X-Forwarded-For}. On n'analyse donc
 * jamais l'en-tête soi-même, ce qui serait falsifiable.</p>
 *
 * <p>Le compteur étant en mémoire, il est par IP et par instance : un déploiement multi-instance
 * nécessiterait un stockage partagé (Redis). C'est volontairement simple ici — monolithique.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String RESET_PATH = "/api/auth/request-reset";
    private static final String TOO_MANY = "Trop de tentatives. Veuillez patienter quelques secondes puis réessayer.";
    private static final String LOCKED = "Trop de tentatives échouées depuis votre adresse. "
            + "L'accès est bloqué temporairement pour raisons de sécurité.";

    private final boolean enabled;
    private final int loginMaxRequests;
    private final int resetMaxRequests;
    private final long windowMillis;
    private final int maxFailures;
    private final long lockoutMillis;

    /** Fenêtre glissante : une file d'horodatages par « IP + endpoint ». */
    private final ConcurrentHashMap<String, SlidingWindow> windows = new ConcurrentHashMap<>();
    /** Échecs d'identification : un compteur + une date de fin de blocage par IP. */
    private final ConcurrentHashMap<String, FailureState> failures = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong(System.currentTimeMillis());

    public RateLimitFilter(
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.login.max-requests:10}") int loginMaxRequests,
            @Value("${app.rate-limit.reset.max-requests:5}") int resetMaxRequests,
            @Value("${app.rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${app.rate-limit.max-failures:5}") int maxFailures,
            @Value("${app.rate-limit.lockout-minutes:15}") long lockoutMinutes) {
        this.enabled = enabled;
        this.loginMaxRequests = Math.max(1, loginMaxRequests);
        this.resetMaxRequests = Math.max(1, resetMaxRequests);
        this.windowMillis = Math.max(1L, windowSeconds) * 1000L;
        this.maxFailures = Math.max(1, maxFailures);
        this.lockoutMillis = Math.max(1L, lockoutMinutes) * 60_000L;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled || !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !(loginPath(request) || resetPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String ip = request.getRemoteAddr();
        cleanupIfNeeded(now);

        boolean isLogin = loginPath(request);

        // 1) IP bloquée après trop d'échecs d'identification. Le blocage ne vise que la
        //    connexion : un client légitime peut toujours demander un lien de réinitialisation.
        FailureState state = isLogin ? failures.get(ip) : null;
        if (state != null) {
            synchronized (state) {
                if (state.lockedUntil > now) {
                    writeTooManyRequests(response, HttpStatus.TOO_MANY_REQUESTS, TOO_MANY + " "
                            + minutesMessage(state.lockedUntil - now),
                            secondsUntil(state.lockedUntil, now));
                    return;
                }
            }
        }

        // 2) Fenêtre glissante : débit maximal par IP et par endpoint.
        int max = isLogin ? loginMaxRequests : resetMaxRequests;
        SlidingWindow window = windows.computeIfAbsent(ip + "|" + path(request), k -> new SlidingWindow());
        Long retryAfterSeconds;
        synchronized (window) {
            retryAfterSeconds = window.tryAcquire(now, windowMillis, max);
        }
        if (retryAfterSeconds != null) {
            String message = isLogin ? TOO_MANY + " " + secondsMessage(retryAfterSeconds)
                    : "Trop de demandes de réinitialisation. " + secondsMessage(retryAfterSeconds);
            writeTooManyRequests(response, HttpStatus.TOO_MANY_REQUESTS, message, retryAfterSeconds);
            return;
        }

        chain.doFilter(request, response);

        // 3) Bilan de la tentative d'identification.
        if (isLogin) {
            int status = response.getStatus();
            if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
                registerFailure(ip, now);
            } else if (status >= 200 && status < 300) {
                failures.remove(ip);
            }
        }
    }

    private void registerFailure(String ip, long now) {
        FailureState state = failures.computeIfAbsent(ip, k -> new FailureState());
        synchronized (state) {
            if (now - state.lastFailureAt > lockoutMillis) {
                state.count = 0; // aucun échec récent depuis la durée de blocage : on repart de zéro
            }
            state.lastFailureAt = now;
            state.count++;
            if (state.count >= maxFailures) {
                state.lockedUntil = now + lockoutMillis;
            }
        }
    }

    /** Tri périodique des entrées mortes : la carte ne peut pas croître sans limite. */
    private void cleanupIfNeeded(long now) {
        long previous = lastCleanup.get();
        if (now - previous < 60_000L || !lastCleanup.compareAndSet(previous, now)) {
            return;
        }
        windows.entrySet().removeIf(e -> {
            SlidingWindow w = e.getValue();
            synchronized (w) {
                w.evictExpired(now, windowMillis);
                return w.isEmpty();
            }
        });
        failures.entrySet().removeIf(e -> {
            FailureState s = e.getValue();
            synchronized (s) {
                return s.lockedUntil < now && now - s.lastFailureAt > lockoutMillis;
            }
        });
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpStatus status,
                                      String message, long retryAfterSeconds) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfterSeconds)));
        // Même forme que les réponses du portail : { "message": "..." }
        response.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }

    private boolean loginPath(HttpServletRequest request) {
        return LOGIN_PATH.equals(path(request));
    }

    private boolean resetPath(HttpServletRequest request) {
        return RESET_PATH.equals(path(request));
    }

    private String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return (context != null && !context.isEmpty() && uri.startsWith(context))
                ? uri.substring(context.length()) : uri;
    }

    private static long secondsUntil(long instant, long now) {
        return Math.max(1, (instant - now + 999) / 1000);
    }

    private static String secondsMessage(long seconds) {
        return "Réessayez dans " + seconds + " seconde(s).";
    }

    private static String minutesMessage(long millis) {
        return "Blocage temporaire : " + Math.max(1, (millis + 59_999) / 60_000) + " minute(s) restantes.";
    }

    /** File des horodatages de la fenêtre courante (glissante, non pas « par minute entière »). */
    private static final class SlidingWindow {
        private final Deque<Long> arrivals = new ArrayDeque<>();

        /** @return null si la requête est acceptée, sinon le nombre de secondes à attendre. */
        Long tryAcquire(long now, long windowMillis, int maxRequests) {
            evictExpired(now, windowMillis);
            if (arrivals.size() >= maxRequests) {
                long oldest = arrivals.peekFirst();
                return Math.max(1, (oldest + windowMillis - now + 999) / 1000);
            }
            arrivals.addLast(now);
            return null;
        }

        void evictExpired(long now, long windowMillis) {
            while (!arrivals.isEmpty() && now - arrivals.peekFirst() >= windowMillis) {
                arrivals.removeFirst();
            }
        }

        boolean isEmpty() {
            return arrivals.isEmpty();
        }
    }

    private static final class FailureState {
        private int count;
        private long lastFailureAt;
        private long lockedUntil;
    }
}
