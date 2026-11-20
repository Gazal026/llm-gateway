package dev.llmgateway.core;

import java.math.BigDecimal;
import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import dev.llmgateway.api.openai.Usage;

/**
 * All custom metrics in one place. Exposed at /actuator/prometheus as gateway_* series.
 * Tags are kept low-cardinality: model alias, provider, tenant, status.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void request(String model, String provider, String status) {
        Counter.builder("gateway.requests")
                .description("Completed gateway requests")
                .tag("model", model).tag("provider", provider).tag("status", status)
                .register(registry).increment();
    }

    public void tokens(String tenant, String provider, Usage usage) {
        tokenCounter(tenant, provider, "prompt").increment(usage.promptTokens());
        tokenCounter(tenant, provider, "completion").increment(usage.completionTokens());
    }

    public void cost(String tenant, String provider, BigDecimal usd) {
        Counter.builder("gateway.cost.usd")
                .description("Estimated spend in USD")
                .tag("tenant", tenant).tag("provider", provider)
                .register(registry).increment(usd.doubleValue());
    }

    public void upstreamLatency(String provider, Duration duration) {
        Timer.builder("gateway.upstream.latency")
                .description("Time for a non-streaming upstream call")
                .tag("provider", provider)
                .publishPercentileHistogram()
                .register(registry).record(duration);
    }

    public void timeToFirstToken(String provider, Duration duration) {
        Timer.builder("gateway.ttft")
                .description("Time from request arrival to the first streamed token")
                .tag("provider", provider)
                .publishPercentileHistogram()
                .register(registry).record(duration);
    }

    public void fallback(String fromProvider, String reason) {
        Counter.builder("gateway.fallbacks")
                .description("Times a request moved on to the next provider in its route")
                .tag("from_provider", fromProvider).tag("reason", reason)
                .register(registry).increment();
    }

    public void rejected(String reason) {
        Counter.builder("gateway.rejections")
                .description("Requests rejected before reaching a provider")
                .tag("reason", reason)
                .register(registry).increment();
    }

    public void failOpen(String component) {
        Counter.builder("gateway.fail.open")
                .description("Times a dependency (e.g. Redis) was unavailable and the gateway let the request through")
                .tag("component", component)
                .register(registry).increment();
    }

    private Counter tokenCounter(String tenant, String provider, String type) {
        return Counter.builder("gateway.tokens")
                .description("Tokens processed")
                .tag("tenant", tenant).tag("provider", provider).tag("type", type)
                .register(registry);
    }
}
