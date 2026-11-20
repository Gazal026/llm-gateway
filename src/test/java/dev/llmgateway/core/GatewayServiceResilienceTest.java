package dev.llmgateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import dev.llmgateway.api.openai.ChatCompletionChunk;
import dev.llmgateway.api.openai.ChatCompletionChunk.Delta;
import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ChatCompletionResponse;
import dev.llmgateway.api.openai.ChatMessage;
import dev.llmgateway.api.openai.Usage;
import dev.llmgateway.auth.ClientIdentity;
import dev.llmgateway.config.GatewayProperties;
import dev.llmgateway.config.GatewayProperties.CircuitBreakerSettings;
import dev.llmgateway.config.GatewayProperties.ProviderType;
import dev.llmgateway.config.GatewayProperties.RouteTarget;
import dev.llmgateway.config.GatewayProperties.Timeouts;
import dev.llmgateway.config.ProviderConfig;
import dev.llmgateway.provider.LlmProvider;
import dev.llmgateway.provider.ProviderRegistry;
import dev.llmgateway.provider.UpstreamException;
import dev.llmgateway.ratelimit.RateLimiter;
import dev.llmgateway.ratelimit.Reservation;
import dev.llmgateway.ratelimit.TokenBudgetService;
import dev.llmgateway.routing.ModelRouter;
import dev.llmgateway.usage.CostCalculator;
import dev.llmgateway.usage.UsageEvent;
import dev.llmgateway.usage.UsageRecorder;

/**
 * Proves the failover rules in {@link GatewayService} with scripted fake providers:
 * circuit breakers open and skip a failing provider, client errors don't trip them,
 * and first-token / idle / total timeouts behave as designed.
 * No Docker, Redis or HTTP: rate limiting, budgets and usage recording are mocked.
 */
class GatewayServiceResilienceTest {

    private static final Duration FIRST_TOKEN_TIMEOUT = Duration.ofMillis(300);
    private static final Duration IDLE_TIMEOUT = Duration.ofMillis(300);
    private static final Duration TOTAL_TIMEOUT = Duration.ofMillis(500);
    /** Small window so a handful of calls is enough to open the breaker. */
    private static final int BREAKER_WINDOW = 4;

    private final ScriptedProvider primary = new ScriptedProvider("primary");
    private final ScriptedProvider secondary = new ScriptedProvider("secondary");
    private final UsageRecorder usageRecorder = mock(UsageRecorder.class);
    private final RequestContext ctx = new RequestContext("req-test",
            new ClientIdentity(UUID.randomUUID(), "test-tenant", UUID.randomUUID(), 1000, null), System.nanoTime());

    private CircuitBreakerRegistry circuitBreakers;
    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        Map<String, List<RouteTarget>> routes = new LinkedHashMap<>();
        routes.put("resilient", List.of(new RouteTarget("primary", "model-a"), new RouteTarget("secondary", "model-b")));
        CircuitBreakerSettings breakerSettings =
                new CircuitBreakerSettings(50f, BREAKER_WINDOW, BREAKER_WINDOW, Duration.ofMinutes(1));
        GatewayProperties properties = new GatewayProperties(
                "admin", null, null, null,
                new Timeouts(FIRST_TOKEN_TIMEOUT, IDLE_TIMEOUT, TOTAL_TIMEOUT),
                breakerSettings, Map.of(), routes, Map.of());

        ProviderRegistry registry = new ProviderRegistry(Map.of("primary", primary, "secondary", secondary));
        circuitBreakers = CircuitBreakerRegistry.of(ProviderConfig.circuitBreakerConfig(breakerSettings));

        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.acquire(any())).thenReturn(Mono.empty());
        TokenBudgetService budgets = mock(TokenBudgetService.class);
        when(budgets.reserve(any(), anyInt(), anyLong())).thenAnswer(invocation ->
                Mono.just(Reservation.unmetered(invocation.<Integer>getArgument(1), invocation.<Long>getArgument(2))));

        gateway = new GatewayService(new ModelRouter(properties, registry), rateLimiter, budgets, usageRecorder,
                new CostCalculator(properties), circuitBreakers, new GatewayMetrics(new SimpleMeterRegistry()),
                properties);

        secondary.completeWith(() -> Mono.just(response("Hello from secondary")));
        secondary.streamWith(() -> Flux.just(chunk("Hello"), chunk(" from secondary"),
                ChatCompletionChunk.ofUsage("id", "model-b", 1L, Usage.of(5, 3))));
    }

    // ---------------------------------------------------------------- circuit breaker

    @Test
    void breakerOpensAfterRepeatedServerErrorsAndThenSkipsTheProvider() {
        primary.completeWith(() -> Mono.error(new UpstreamException("primary", 503, "overloaded")));

        for (int i = 0; i < BREAKER_WINDOW; i++) {
            GatewayResult result = gateway.complete(ctx, request(false)).block(Duration.ofSeconds(5));
            assertThat(result.provider()).isEqualTo("secondary");
        }
        assertThat(breaker("primary").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(primary.calls()).isEqualTo(BREAKER_WINDOW);

        // With the breaker open, the primary isn't even called: the request goes straight to the secondary.
        GatewayResult result = gateway.complete(ctx, request(false)).block(Duration.ofSeconds(5));
        assertThat(result.provider()).isEqualTo("secondary");
        assertThat(result.fallbacks()).isEqualTo(1);
        assertThat(primary.calls()).isEqualTo(BREAKER_WINDOW);
    }

    @Test
    void clientErrorsAreReturnedWithoutFailoverAndDoNotTripTheBreaker() {
        primary.completeWith(() -> Mono.error(new UpstreamException("primary", 400, "bad request")));

        for (int i = 0; i < BREAKER_WINDOW * 2; i++) {
            assertThatThrownBy(() -> gateway.complete(ctx, request(false)).block(Duration.ofSeconds(5)))
                    .isInstanceOfSatisfying(GatewayException.class,
                            error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        // A bad request says nothing about the provider's health, and retrying it elsewhere would just cost money.
        assertThat(breaker("primary").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(secondary.calls()).isZero();
    }

    // ---------------------------------------------------------------- timeouts

    @Test
    void nonStreamingCallFailsOverWhenThePrimaryExceedsTheTotalTimeout() {
        primary.completeWith(Mono::never);

        GatewayResult result = gateway.complete(ctx, request(false)).block(Duration.ofSeconds(5));

        assertThat(result.provider()).isEqualTo("secondary");
        assertThat(result.fallbacks()).isEqualTo(1);
        assertThat(result.response().choices().get(0).message().content()).isEqualTo("Hello from secondary");
    }

    @Test
    void streamFailsOverWhenTheFirstTokenNeverArrives() {
        primary.streamWith(Flux::never);

        List<ChatCompletionChunk> chunks = gateway.stream(ctx, request(true))
                .flatMapMany(stream -> stream)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).extracting(c -> c.choices().get(0).delta().content())
                .containsExactly("Hello", " from secondary");
        assertThat(primary.calls()).isEqualTo(1);
        assertThat(secondary.calls()).isEqualTo(1);
    }

    @Test
    void streamDoesNotFailOverOnceTokensHaveReachedTheClient() {
        // The first chunk arrives, then the provider dies. Switching now would splice two different answers.
        primary.streamWith(() -> Flux.concat(
                Flux.just(chunk("Hel")),
                Flux.error(new UpstreamException("primary", 503, "connection reset"))));

        StepVerifier.create(gateway.stream(ctx, request(true)).flatMapMany(stream -> stream))
                .assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hel"))
                .expectErrorSatisfies(error -> assertThat(((GatewayException) error).status())
                        .isEqualTo(HttpStatus.BAD_GATEWAY))
                .verify(Duration.ofSeconds(5));

        assertThat(secondary.calls()).isZero();

        // The partial answer is still accounted for: an error event with estimated completion tokens.
        ArgumentCaptor<UsageEvent> event = ArgumentCaptor.forClass(UsageEvent.class);
        verify(usageRecorder, timeout(1000)).record(event.capture());
        assertThat(event.getValue().status()).isEqualTo("error");
        assertThat(event.getValue().streamed()).isTrue();
        assertThat(event.getValue().tokensEstimated()).isTrue();
        assertThat(event.getValue().completionTokens()).isPositive();
    }

    @Test
    void streamTimesOutWhenTheProviderGoesSilentMidAnswer() {
        primary.streamWith(() -> Flux.concat(Flux.just(chunk("Hel")), Flux.never()));

        StepVerifier.create(gateway.stream(ctx, request(true)).flatMapMany(stream -> stream))
                .expectNextCount(1)
                .expectErrorSatisfies(error -> assertThat(((GatewayException) error).status())
                        .isEqualTo(HttpStatus.GATEWAY_TIMEOUT))
                .verify(Duration.ofSeconds(5));

        assertThat(secondary.calls()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private CircuitBreaker breaker(String provider) {
        return circuitBreakers.circuitBreaker(provider);
    }

    private static ChatCompletionRequest request(boolean stream) {
        return new ChatCompletionRequest("resilient", List.of(ChatMessage.user("Hi")), stream, null,
                null, null, null, null, null, null);
    }

    private static ChatCompletionResponse response(String text) {
        return new ChatCompletionResponse("id", ChatCompletionResponse.OBJECT, 1L, "model-b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant(text), "stop")),
                Usage.of(5, 3));
    }

    private static ChatCompletionChunk chunk(String text) {
        return ChatCompletionChunk.delta("id", "model", 1L, new Delta(null, text), null);
    }

    /** A fake provider whose behaviour each test scripts, and which counts how often it was really called. */
    private static final class ScriptedProvider implements LlmProvider {

        private final String name;
        private final AtomicInteger calls = new AtomicInteger();
        private Supplier<Mono<ChatCompletionResponse>> complete = () -> Mono.error(new IllegalStateException("not scripted"));
        private Supplier<Flux<ChatCompletionChunk>> stream = () -> Flux.error(new IllegalStateException("not scripted"));

        ScriptedProvider(String name) {
            this.name = name;
        }

        void completeWith(Supplier<Mono<ChatCompletionResponse>> behaviour) {
            this.complete = behaviour;
        }

        void streamWith(Supplier<Flux<ChatCompletionChunk>> behaviour) {
            this.stream = behaviour;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ProviderType type() {
            return ProviderType.MOCK;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
            calls.incrementAndGet();
            return complete.get();
        }

        @Override
        public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
            calls.incrementAndGet();
            return stream.get();
        }
    }
}
