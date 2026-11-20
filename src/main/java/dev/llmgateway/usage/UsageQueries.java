package dev.llmgateway.usage;

import java.math.BigDecimal;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Read side for usage reports (feeds the admin API and, next, the Angular dashboard). */
@Component
public class UsageQueries {

    private static final String SUMMARY = """
            SELECT t.name                                         AS tenant,
                   u.provider                                     AS provider,
                   count(*)                                       AS requests,
                   count(*) FILTER (WHERE u.status = 'error')     AS errors,
                   coalesce(sum(u.prompt_tokens), 0)              AS prompt_tokens,
                   coalesce(sum(u.completion_tokens), 0)          AS completion_tokens,
                   coalesce(sum(u.cost_usd), 0)                   AS cost_usd,
                   coalesce(avg(u.latency_ms), 0)::int            AS avg_latency_ms,
                   coalesce(sum(u.fallback_count), 0)             AS fallbacks
            FROM usage_events u
            JOIN tenants t ON t.id = u.tenant_id
            WHERE u.created_at > now() - make_interval(hours => :hours)
            GROUP BY t.name, u.provider
            ORDER BY cost_usd DESC, requests DESC
            """;

    private final DatabaseClient db;

    public UsageQueries(DatabaseClient db) {
        this.db = db;
    }

    public Flux<UsageSummary> summary(int hours) {
        return db.sql(SUMMARY)
                .bind("hours", hours)
                .map((row, metadata) -> new UsageSummary(
                        row.get("tenant", String.class),
                        row.get("provider", String.class),
                        row.get("requests", Long.class),
                        row.get("errors", Long.class),
                        row.get("prompt_tokens", Long.class),
                        row.get("completion_tokens", Long.class),
                        row.get("cost_usd", BigDecimal.class),
                        row.get("avg_latency_ms", Integer.class),
                        row.get("fallbacks", Long.class)))
                .all();
    }

    public record UsageSummary(
            String tenant,
            String provider,
            long requests,
            long errors,
            long promptTokens,
            long completionTokens,
            BigDecimal costUsd,
            int avgLatencyMs,
            long fallbacks) {}
}
