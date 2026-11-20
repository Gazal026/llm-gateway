package dev.llmgateway.core;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import dev.llmgateway.api.openai.ChatCompletionChunk;
import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ChatCompletionResponse;
import dev.llmgateway.api.openai.Usage;
import dev.llmgateway.config.GatewayProperties;
import dev.llmgateway.ratelimit.RateLimiter;
import dev.llmgateway.ratelimit.Reservation;
import dev.llmgateway.ratelimit.TokenBudgetService;
import dev.llmgateway.routing.ModelRouter;
import dev.llmgateway.routing.ResolvedTarget;
import dev.llmgateway.usage.CostCalculator;
import dev.llmgateway.usage.UsageEvent;
import dev.llmgateway.usage.UsageRecorder;

/**
 * The request pipeline:
 * <pre>
 *   resolve route -> rate limit -> reserve budget -> try providers in order (circuit breaker + timeouts)
 *                 -> reconcile budget -> record usage + metrics
 * </pre>
 * Failover rules:
 * <ul>
 *   <li>Non-streaming: any retryable error moves on to the next provider.</li>
 *   <li>Streaming: failover is only possible before the first chunk reaches the client. After that,
 *       switching providers would splice two different answers together, so the error is surfaced instead.</li>
 * </ul>
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final ModelRouter router;
    private final RateLimiter rateLimiter;
    private final TokenBudgetService budgets;
    private final UsageRecorder usageRecorder;
    private final CostCalculator costs;
    private final CircuitBreakerRegistry circuitBreakers;
    private final GatewayMetrics metrics;
    private final GatewayProperties properties;

    public GatewayService(ModelRouter router, RateLimiter rateLimiter, TokenBudgetService budgets,
                          UsageRecorder usageRecorder, CostCalculator costs, CircuitBreakerRegistry circuitBreakers,
                          GatewayMetrics metrics, GatewayProperties properties) {
        this.router = router;
        this.rateLimiter = rateLimiter;
        this.budgets = budgets;
        this.usageRecorder = usageRecorder;
        this.costs = costs;
        this.circuitBreakers = circuitBreakers;
        this.metrics = metrics;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- non-streaming

    public Mono<GatewayResult> complete(RequestContext ctx, ChatCompletionRequest request) {
        return Mono.defer(() -> {
                    List<ResolvedTarget> targets = router.resolve(request.model());
                    return admit(ctx, request).flatMap(reservation -> {
                        CallState state = new CallState();
                        return completeAttempt(request, targets, 0, state)
                                .doOnNext(state::observe)
                                .map(response -> new GatewayResult(response, state.providerName(), state.fallbacks()))
                                .doOnError(state::failed)
                                .doFinally(signal -> finish(ctx, request, reservation, state, signal, false));
                    });
                })
                .onErrorMap(GatewayException::from);
    }

    private Mono<ChatCompletionResponse> completeAttempt(ChatCompletionRequest request, List<ResolvedTarget> targets,
                                                         int index, CallState state) {
        ResolvedTarget target = targets.get(index);
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(target.providerName());
        return Mono.defer(() -> {
                    state.startAttempt(target);
                    return target.provider().complete(request.forUpstream(target.model(), false));
                })
                .timeout(properties.timeouts().total())
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .doOnNext(response -> metrics.upstreamLatency(target.providerName(),
                        Duration.ofNanos(System.nanoTime() - state.attemptStartNanos())))
                .onErrorResume(
                        error -> Failures.isRetryable(error) && index + 1 < targets.size(),
                        error -> {
                            recordFallback(target, error, state);
                            return completeAttempt(request, targets, index + 1, state);
                        });
    }

    // ---------------------------------------------------------------- streaming

    /**
     * Admission (routing, rate limit, budget) happens in the returned Mono, so those failures still map to proper
     * HTTP statuses (404/429/503). Once the Flux is returned the response is committed as 200 + event stream.
     */
    public Mono<Flux<ChatCompletionChunk>> stream(RequestContext ctx, ChatCompletionRequest request) {
        return Mono.defer(() -> {
                    List<ResolvedTarget> targets = router.resolve(request.model());
                    return admit(ctx, request).map(reservation -> {
                        CallState state = new CallState();
                        return streamAttempt(request, targets, 0, state)
                                .doOnNext(state::observe)
                                // Clients only get the usage chunk if they asked for it (OpenAI semantics).
                                .filter(chunk -> request.includeUsage() || !chunk.carriesOnlyUsage())
                                .doOnError(state::failed)
                                .doFinally(signal -> finish(ctx, request, reservation, state, signal, true))
                                .onErrorMap(GatewayException::from);
                    });
                })
                .onErrorMap(GatewayException::from);
    }

    private Flux<ChatCompletionChunk> streamAttempt(ChatCompletionRequest request, List<ResolvedTarget> targets,
                                                    int index, CallState state) {
        ResolvedTarget target = targets.get(index);
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(target.providerName());
        AtomicBoolean emitted = new AtomicBoolean(false);
        return Flux.defer(() -> {
                    state.startAttempt(target);
                    return target.provider().stream(request.forUpstream(target.model(), true));
                })
                // First chunk must arrive within first-token timeout; afterwards, no gap longer than idle timeout.
                .timeout(Mono.delay(properties.timeouts().firstToken()),
                        chunk -> Mono.delay(properties.timeouts().idle()))
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .doOnNext(chunk -> emitted.set(true))
                .onErrorResume(
                        error -> !emitted.get() && Failures.isRetryable(error) && index + 1 < targets.size(),
                        error -> {
                            recordFallback(target, error, state);
                            return streamAttempt(request, targets, index + 1, state);
                        });
    }

    // ---------------------------------------------------------------- shared steps

    private Mono<Reservation> admit(RequestContext ctx, ChatCompletionRequest request) {
        int promptEstimate = TokenEstimator.estimatePrompt(request.messages());
        int maxOutput = request.maxOutputTokens() != null ? request.maxOutputTokens() : properties.defaults().maxTokens();
        return rateLimiter.acquire(ctx.client())
                .then(Mono.defer(() -> budgets.reserve(ctx.client(), promptEstimate, (long) promptEstimate + maxOutput)));
    }

    private void recordFallback(ResolvedTarget target, Throwable error, CallState state) {
        log.warn("Provider '{}' failed ({}); trying next provider", target.providerName(), error.toString());
        metrics.fallback(target.providerName(), Failures.reason(error));
        state.fellBack(error);
    }

    /** Runs exactly once per call (success, error or client disconnect): settle the budget, record usage, emit metrics. */
    private void finish(RequestContext ctx, ChatCompletionRequest request, Reservation reservation,
                        CallState state, SignalType signal, boolean streamed) {
        try {
            String status = switch (signal) {
                case ON_COMPLETE -> "success";
                case CANCEL -> "cancelled";
                default -> "error";
            };
            boolean producedOutput = state.completionChars() > 0;
            boolean estimated = state.usage() == null;
            Usage usage;
            if (!estimated) {
                usage = state.usage();
            } else if ("success".equals(status) || producedOutput) {
                usage = Usage.of(reservation.promptEstimate(), TokenEstimator.fromChars(state.completionChars()));
            } else {
                usage = Usage.zero(); // failed before generating anything: assume the provider didn't bill us
            }

            ResolvedTarget target = state.target();
            String provider = state.providerName();
            String upstreamModel = target == null ? null : target.model();
            BigDecimal cost = costs.cost(upstreamModel, usage);
            long now = System.nanoTime();
            int latencyMs = (int) Duration.ofNanos(now - ctx.startNanos()).toMillis();
            Integer ttftMs = state.firstTokenNanos() > 0
                    ? (int) Duration.ofNanos(state.firstTokenNanos() - ctx.startNanos()).toMillis()
                    : null;
            String tenant = ctx.client().tenantName();

            budgets.reconcile(reservation, usage.totalTokens());
            metrics.request(request.model(), provider, status);
            metrics.tokens(tenant, provider, usage);
            metrics.cost(tenant, provider, cost);
            if (streamed && ttftMs != null) {
                metrics.timeToFirstToken(provider, Duration.ofMillis(ttftMs));
            }

            usageRecorder.record(new UsageEvent(
                    ctx.requestId(), ctx.client().tenantId(), ctx.client().apiKeyId(), request.model(), provider,
                    upstreamModel, status, streamed, usage.promptTokens(), usage.completionTokens(), estimated, cost,
                    latencyMs, ttftMs, state.fallbacks(),
                    "error".equals(status) && state.error() != null ? state.error().getMessage() : null));

            log.info("request={} tenant={} model={} provider={} status={} tokens={}+{}{} cost=${} latency={}ms fallbacks={}",
                    ctx.requestId(), tenant, request.model(), provider, status, usage.promptTokens(),
                    usage.completionTokens(), estimated ? "(est)" : "", cost.toPlainString(), latencyMs, state.fallbacks());
        } catch (RuntimeException e) {
            // Bookkeeping must never break the response path.
            log.error("Failed to finalize request {}", ctx.requestId(), e);
        }
    }
}
