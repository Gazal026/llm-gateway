package dev.llmgateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import dev.llmgateway.api.openai.ChatCompletionChunk;
import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ChatCompletionResponse;
import dev.llmgateway.config.GatewayProperties;
import dev.llmgateway.config.GatewayProperties.ProviderType;
import dev.llmgateway.config.GatewayProperties.RouteTarget;
import dev.llmgateway.core.GatewayException;
import dev.llmgateway.provider.LlmProvider;
import dev.llmgateway.provider.ProviderRegistry;

class ModelRouterTest {

    private final ProviderRegistry registry = new ProviderRegistry(Map.of(
            "primary", new StubProvider("primary", true),
            "secondary", new StubProvider("secondary", true),
            "no-key", new StubProvider("no-key", false)));

    @Test
    void aliasResolvesToTargetsInOrderAndSkipsUnconfiguredProviders() {
        ModelRouter router = router(Map.of("fast", List.of(
                new RouteTarget("no-key", "model-a"),
                new RouteTarget("primary", "model-b"),
                new RouteTarget("secondary", "model-c"))));

        List<ResolvedTarget> targets = router.resolve("fast");

        assertThat(targets).extracting(ResolvedTarget::providerName).containsExactly("primary", "secondary");
        assertThat(targets).extracting(ResolvedTarget::model).containsExactly("model-b", "model-c");
    }

    @Test
    void providerSlashModelBypassesAliases() {
        ModelRouter router = router(Map.of());

        List<ResolvedTarget> targets = router.resolve("secondary/some-model");

        assertThat(targets).singleElement().satisfies(target -> {
            assertThat(target.providerName()).isEqualTo("secondary");
            assertThat(target.model()).isEqualTo("some-model");
        });
    }

    @Test
    void unknownModelIs404() {
        ModelRouter router = router(Map.of());

        assertThatThrownBy(() -> router.resolve("does-not-exist"))
                .isInstanceOf(GatewayException.class)
                .satisfies(error -> assertThat(((GatewayException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void routeWithOnlyUnconfiguredProvidersIs503() {
        ModelRouter router = router(Map.of("cloud-only", List.of(new RouteTarget("no-key", "model-a"))));

        assertThatThrownBy(() -> router.resolve("cloud-only"))
                .isInstanceOf(GatewayException.class)
                .satisfies(error -> assertThat(((GatewayException) error).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void typoInRouteConfigFailsAtStartup() {
        assertThatThrownBy(() -> router(Map.of("broken", List.of(new RouteTarget("primray", "model-a")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primray");
    }

    private ModelRouter router(Map<String, List<RouteTarget>> routes) {
        GatewayProperties properties = new GatewayProperties(
                "admin", null, null, null, null, null, Map.of(), new LinkedHashMap<>(routes), Map.of());
        return new ModelRouter(properties, registry);
    }

    private record StubProvider(String name, boolean configured) implements LlmProvider {

        @Override
        public ProviderType type() {
            return ProviderType.MOCK;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
            return Mono.empty();
        }

        @Override
        public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
            return Flux.empty();
        }
    }
}
