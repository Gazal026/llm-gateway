package dev.llmgateway.usage;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;

/**
 * Writes usage events off the request path: the client never waits for this insert.
 * Trade-off: an event can be lost if the process dies mid-write. The roadmap item is to publish
 * events to Kafka (or a transactional outbox) for at-least-once delivery.
 */
@Component
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);
    private static final int MAX_ERROR_CHARS = 500;

    private static final String INSERT = """
            INSERT INTO usage_events (request_id, tenant_id, api_key_id, requested_model, provider, upstream_model,
                                      status, streamed, prompt_tokens, completion_tokens, tokens_estimated, cost_usd,
                                      latency_ms, ttft_ms, fallback_count, error)
            VALUES (:requestId, :tenantId, :apiKeyId, :requestedModel, :provider, :upstreamModel,
                    :status, :streamed, :promptTokens, :completionTokens, :tokensEstimated, :costUsd,
                    :latencyMs, :ttftMs, :fallbackCount, :error)
            """;

    private final DatabaseClient db;

    public UsageRecorder(DatabaseClient db) {
        this.db = db;
    }

    public void record(UsageEvent event) {
        GenericExecuteSpec spec = db.sql(INSERT)
                .bind("requestId", event.requestId())
                .bind("tenantId", event.tenantId())
                .bind("requestedModel", event.requestedModel())
                .bind("provider", event.provider())
                .bind("status", event.status())
                .bind("streamed", event.streamed())
                .bind("promptTokens", event.promptTokens())
                .bind("completionTokens", event.completionTokens())
                .bind("tokensEstimated", event.tokensEstimated())
                .bind("costUsd", event.costUsd())
                .bind("latencyMs", event.latencyMs())
                .bind("fallbackCount", event.fallbackCount());
        spec = bindNullable(spec, "apiKeyId", event.apiKeyId(), UUID.class);
        spec = bindNullable(spec, "upstreamModel", event.upstreamModel(), String.class);
        spec = bindNullable(spec, "ttftMs", event.ttftMs(), Integer.class);
        spec = bindNullable(spec, "error", truncate(event.error()), String.class);

        spec.then().subscribe(
                null,
                error -> log.warn("Failed to record usage for request {}: {}", event.requestId(), error.toString()));
    }

    private static <T> GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_CHARS) {
            return value;
        }
        return value.substring(0, MAX_ERROR_CHARS);
    }
}
