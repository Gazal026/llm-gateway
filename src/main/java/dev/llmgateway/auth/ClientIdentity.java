package dev.llmgateway.auth;

import java.util.UUID;

/** Who is calling: resolved from the API key on every request (cached briefly). */
public record ClientIdentity(
        UUID tenantId,
        String tenantName,
        UUID apiKeyId,
        int requestsPerMinute,
        Long dailyTokenBudget) {
}
