package dev.llmgateway.usage;

import java.math.BigDecimal;
import java.util.UUID;

/** One row in usage_events: what a request cost and how it was served. */
public record UsageEvent(
        String requestId,
        UUID tenantId,
        UUID apiKeyId,
        String requestedModel,
        String provider,
        String upstreamModel,
        String status,
        boolean streamed,
        int promptTokens,
        int completionTokens,
        boolean tokensEstimated,
        BigDecimal costUsd,
        int latencyMs,
        Integer ttftMs,
        int fallbackCount,
        String error) {
}
