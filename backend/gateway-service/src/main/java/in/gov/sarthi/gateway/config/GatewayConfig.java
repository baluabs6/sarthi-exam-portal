package in.gov.sarthi.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Rate limiting is applied here, at the gateway, rather than deep inside
 * queue-service or result-service. This means an overload never reaches
 * the databases in the first place: excess requests are shed (HTTP 429)
 * or queued at the edge, which is what keeps the origin services from
 * ever tipping over during a result-day spike.
 */
@Configuration
public class GatewayConfig {

    /**
     * Requests are keyed by client IP so one flooding client can't starve
     * others. In production this would additionally consider the
     * X-Forwarded-For chain from the CDN.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown"
        );
    }

    /**
     * Sustained rate of 20 req/s per client, burst capacity of 40.
     * Tuned per-route via application.yml; this bean just provides the
     * algorithm (token bucket, backed by Redis so it works correctly
     * across multiple gateway replicas).
     */
    @Bean
    public RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(20, 40, 1);
    }

    /**
     * Explicit CORS allowlist, applied at the single edge every request
     * passes through. Previously there was no CORS policy configured at
     * all here (each downstream service was left to its own defaults),
     * which is easy to get wrong per-service. Origins come from
     * ALLOWED_ORIGINS so this stays in sync with the frontend's actual
     * deployed URL(s).
     */
    @Bean
    public CorsWebFilter corsWebFilter(@Value("${security.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Admin-Key", "Idempotency-Key", "X-Partner-Key"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }

    /**
     * Standard defensive response headers for a public government portal
     * — a natural phishing/clone target. Applied globally so no
     * individual route can accidentally ship without them.
     */
    @Bean
    public GlobalFilter securityHeadersFilter() {
        return (exchange, chain) -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.add("X-Content-Type-Options", "nosniff");
            headers.add("X-Frame-Options", "DENY");
            headers.add("Referrer-Policy", "strict-origin-when-cross-origin");
            headers.add("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
            headers.add("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            return chain.filter(exchange);
        };
    }
}
