package dev.llmgateway.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of everything under {@code gateway.*} in application.yml.
 * Compact constructors fill in defaults so the rest of the code never deals with nulls.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        String adminKey,
        Bootstrap bootstrap,
        List<String> corsAllowedOrigins,
        Defaults defaults,
        Timeouts timeouts,
        CircuitBreakerSettings circuitBreaker,
        Map<String, ProviderSettings> providers,
        Map<String, List<RouteTarget>> routes,
        Map<String, Price> pricing) {

    public GatewayProperties {
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
        defaults = defaults == null ? new Defaults(null) : defaults;
        timeouts = timeouts == null ? new Timeouts(null, null, null) : timeouts;
        circuitBreaker = circuitBreaker == null ? new CircuitBreakerSettings(null, null, null, null) : circuitBreaker;
        // Keep YAML order: it is the order providers are listed in logs and the admin API.
        providers = providers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        routes = routes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(routes));
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
    }

    public record Bootstrap(String apiKey, String tenantName, Integer requestsPerMinute, Long dailyTokenBudget) {
        public Bootstrap {
            tenantName = tenantName == null || tenantName.isBlank() ? "default" : tenantName;
            requestsPerMinute = requestsPerMinute == null || requestsPerMinute <= 0 ? 60 : requestsPerMinute;
            dailyTokenBudget = dailyTokenBudget == null || dailyTokenBudget <= 0 ? null : dailyTokenBudget;
        }
    }

    public record Defaults(Integer maxTokens) {
        public Defaults {
            maxTokens = maxTokens == null || maxTokens <= 0 ? 1024 : maxTokens;
        }
    }

    public record Timeouts(Duration firstToken, Duration idle, Duration total) {
        public Timeouts {
            firstToken = firstToken == null ? Duration.ofSeconds(20) : firstToken;
            idle = idle == null ? Duration.ofSeconds(30) : idle;
            total = total == null ? Duration.ofSeconds(120) : total;
        }
    }

    public record CircuitBreakerSettings(
            Float failureRateThreshold,
            Integer slidingWindowSize,
            Integer minimumNumberOfCalls,
            Duration waitDurationInOpenState) {
        public CircuitBreakerSettings {
            failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
            slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
            minimumNumberOfCalls = minimumNumberOfCalls == null ? 10 : minimumNumberOfCalls;
            waitDurationInOpenState = waitDurationInOpenState == null ? Duration.ofSeconds(30) : waitDurationInOpenState;
        }
    }

    public record ProviderSettings(
            ProviderType type,
            String baseUrl,
            String apiKey,
            Double failureRate,
            Integer tokensPerSecond) {
        public ProviderSettings {
            failureRate = failureRate == null ? 0.0 : failureRate;
            tokensPerSecond = tokensPerSecond == null || tokensPerSecond <= 0 ? 40 : tokensPerSecond;
        }
    }

    public enum ProviderType { OPENAI, ANTHROPIC, MOCK }

    public record RouteTarget(String provider, String model) {}

    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
        public Price {
            inputPerMillion = inputPerMillion == null ? BigDecimal.ZERO : inputPerMillion;
            outputPerMillion = outputPerMillion == null ? BigDecimal.ZERO : outputPerMillion;
        }
    }
}
