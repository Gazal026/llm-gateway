package dev.llmgateway.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import dev.llmgateway.config.GatewayProperties.CircuitBreakerSettings;
import dev.llmgateway.config.GatewayProperties.ProviderSettings;
import dev.llmgateway.core.Failures;
import dev.llmgateway.provider.LlmProvider;
import dev.llmgateway.provider.ProviderRegistry;
import dev.llmgateway.provider.anthropic.AnthropicProvider;
import dev.llmgateway.provider.mock.MockProvider;
import dev.llmgateway.provider.openai.OpenAiCompatibleProvider;

@Configuration
public class ProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfig.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_IN_MEMORY_BYTES = 8 * 1024 * 1024;

    @Bean
    ProviderRegistry providerRegistry(GatewayProperties properties, WebClient.Builder webClientBuilder, ObjectMapper mapper) {
        // Timeouts for model calls are applied per request in GatewayService (first token / idle / total),
        // so the HTTP client only bounds connection setup.
        HttpClient httpClient = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS);
        WebClient.Builder base = webClientBuilder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES));

        Map<String, LlmProvider> providers = new LinkedHashMap<>();
        properties.providers().forEach((name, settings) -> {
            LlmProvider provider = create(name, settings, base.clone(), mapper, properties.defaults().maxTokens());
            providers.put(name, provider);
            log.info("Provider '{}' ({}) {}", name, settings.type(), provider.isConfigured() ? "ready" : "NOT configured");
        });
        return new ProviderRegistry(providers);
    }

    private static LlmProvider create(String name, ProviderSettings settings, WebClient.Builder builder,
                                      ObjectMapper mapper, int defaultMaxTokens) {
        if (settings.type() == null) {
            throw new IllegalStateException("gateway.providers." + name + ".type is required (openai, anthropic or mock)");
        }
        return switch (settings.type()) {
            case OPENAI -> new OpenAiCompatibleProvider(name, settings, builder, mapper);
            case ANTHROPIC -> new AnthropicProvider(name, settings, builder, mapper, defaultMaxTokens);
            case MOCK -> new MockProvider(name, settings);
        };
    }

    /** One circuit breaker per provider, so an outage at one vendor doesn't slow down requests to the others. */
    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(GatewayProperties properties, MeterRegistry meterRegistry) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(circuitBreakerConfig(properties.circuitBreaker()));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        properties.providers().keySet().forEach(registry::circuitBreaker);
        return registry;
    }

    /** The breaker policy, kept public and static so tests exercise exactly what production uses. */
    public static CircuitBreakerConfig circuitBreakerConfig(CircuitBreakerSettings settings) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(3)
                // LLM streams are legitimately long; don't let duration alone trip the breaker.
                .slowCallDurationThreshold(Duration.ofMinutes(10))
                .recordException(Failures::countsAgainstProvider)
                .build();
    }
}
