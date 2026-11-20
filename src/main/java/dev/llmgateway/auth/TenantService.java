package dev.llmgateway.auth;

import java.time.Duration;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import dev.llmgateway.core.GatewayException;

/** Tenants and their API keys, stored in Postgres. */
@Service
public class TenantService {

    /**
     * Key lookups are cached so the hot path doesn't hit Postgres on every request.
     * Trade-off: a revoked key keeps working on other instances for up to this long.
     */
    private static final Duration KEY_CACHE_TTL = Duration.ofSeconds(60);
    private static final int DEFAULT_REQUESTS_PER_MINUTE = 60;

    private static final String LOOKUP_KEY = """
            SELECT k.id AS key_id, t.id AS tenant_id, t.name, t.requests_per_minute, t.daily_token_budget
            FROM api_keys k
            JOIN tenants t ON t.id = k.tenant_id
            WHERE k.key_hash = :hash AND k.revoked_at IS NULL
            """;

    private static final String LIST_TENANTS = """
            SELECT t.id, t.name, t.requests_per_minute, t.daily_token_budget,
                   count(k.id) FILTER (WHERE k.revoked_at IS NULL) AS active_keys
            FROM tenants t
            LEFT JOIN api_keys k ON k.tenant_id = t.id
            GROUP BY t.id
            ORDER BY t.created_at
            """;

    private final DatabaseClient db;
    private final Cache<String, ClientIdentity> keyCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(KEY_CACHE_TTL)
            .build();

    public TenantService(DatabaseClient db) {
        this.db = db;
    }

    public Mono<ClientIdentity> findByApiKey(String rawKey) {
        String hash = ApiKeys.hash(rawKey);
        ClientIdentity cached = keyCache.getIfPresent(hash);
        if (cached != null) {
            return Mono.just(cached);
        }
        return db.sql(LOOKUP_KEY)
                .bind("hash", hash)
                .map((row, metadata) -> new ClientIdentity(
                        row.get("tenant_id", UUID.class),
                        row.get("name", String.class),
                        row.get("key_id", UUID.class),
                        row.get("requests_per_minute", Integer.class),
                        row.get("daily_token_budget", Long.class)))
                .one()
                .doOnNext(identity -> keyCache.put(hash, identity));
    }

    @Transactional
    public Mono<CreatedKey> createTenant(String name, Integer requestsPerMinute, Long dailyTokenBudget) {
        if (name == null || name.isBlank()) {
            return Mono.error(GatewayException.badRequest("'name' is required."));
        }
        if (requestsPerMinute != null && requestsPerMinute <= 0) {
            return Mono.error(GatewayException.badRequest("'requests_per_minute' must be positive."));
        }
        if (dailyTokenBudget != null && dailyTokenBudget <= 0) {
            return Mono.error(GatewayException.badRequest("'daily_token_budget' must be positive (omit for unlimited)."));
        }
        UUID tenantId = UUID.randomUUID();
        GenericExecuteSpec insert = db.sql("""
                        INSERT INTO tenants (id, name, requests_per_minute, daily_token_budget)
                        VALUES (:id, :name, :rpm, :budget)
                        """)
                .bind("id", tenantId)
                .bind("name", name.trim())
                .bind("rpm", requestsPerMinute == null ? DEFAULT_REQUESTS_PER_MINUTE : requestsPerMinute);
        insert = dailyTokenBudget == null ? insert.bindNull("budget", Long.class) : insert.bind("budget", dailyTokenBudget);
        return insert.then().then(Mono.defer(() -> insertKey(tenantId, ApiKeys.generate())));
    }

    public Mono<CreatedKey> createKey(UUID tenantId) {
        return db.sql("SELECT id FROM tenants WHERE id = :id")
                .bind("id", tenantId)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one()
                .switchIfEmpty(Mono.error(() -> GatewayException.notFound("Tenant " + tenantId + " not found.")))
                .flatMap(id -> insertKey(id, ApiKeys.generate()));
    }

    public Mono<Boolean> revokeKey(UUID keyId) {
        return db.sql("UPDATE api_keys SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .bind("id", keyId)
                .fetch()
                .rowsUpdated()
                .map(updated -> updated > 0)
                // Local cache is keyed by hash, so clear it all; other instances catch up within KEY_CACHE_TTL.
                .doOnNext(revoked -> keyCache.invalidateAll());
    }

    public Flux<TenantView> listTenants() {
        return db.sql(LIST_TENANTS)
                .map((row, metadata) -> new TenantView(
                        row.get("id", UUID.class),
                        row.get("name", String.class),
                        row.get("requests_per_minute", Integer.class),
                        row.get("daily_token_budget", Long.class),
                        row.get("active_keys", Long.class)))
                .all();
    }

    /** Idempotently creates the configured dev tenant and key at startup (and applies config changes to its limits). */
    public Mono<Void> ensureBootstrapTenant(String name, String rawKey, int requestsPerMinute, Long dailyTokenBudget) {
        GenericExecuteSpec upsert = db.sql("""
                        INSERT INTO tenants (id, name, requests_per_minute, daily_token_budget)
                        VALUES (:id, :name, :rpm, :budget)
                        ON CONFLICT (name) DO UPDATE
                            SET requests_per_minute = EXCLUDED.requests_per_minute,
                                daily_token_budget  = EXCLUDED.daily_token_budget
                        """)
                .bind("id", UUID.randomUUID())
                .bind("name", name)
                .bind("rpm", requestsPerMinute);
        upsert = dailyTokenBudget == null ? upsert.bindNull("budget", Long.class) : upsert.bind("budget", dailyTokenBudget);

        Mono<UUID> tenantId = db.sql("SELECT id FROM tenants WHERE name = :name")
                .bind("name", name)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one();

        return upsert.then()
                .then(tenantId)
                .flatMap(id -> db.sql("""
                                INSERT INTO api_keys (id, tenant_id, key_prefix, key_hash)
                                VALUES (:id, :tenantId, :prefix, :hash)
                                ON CONFLICT (key_hash) DO NOTHING
                                """)
                        .bind("id", UUID.randomUUID())
                        .bind("tenantId", id)
                        .bind("prefix", ApiKeys.displayPrefix(rawKey))
                        .bind("hash", ApiKeys.hash(rawKey))
                        .then());
    }

    private Mono<CreatedKey> insertKey(UUID tenantId, String rawKey) {
        UUID keyId = UUID.randomUUID();
        return db.sql("""
                        INSERT INTO api_keys (id, tenant_id, key_prefix, key_hash)
                        VALUES (:id, :tenantId, :prefix, :hash)
                        """)
                .bind("id", keyId)
                .bind("tenantId", tenantId)
                .bind("prefix", ApiKeys.displayPrefix(rawKey))
                .bind("hash", ApiKeys.hash(rawKey))
                .then()
                .thenReturn(new CreatedKey(tenantId, keyId, rawKey, ApiKeys.displayPrefix(rawKey)));
    }

    /** Returned once at creation: the only time the plaintext key is ever visible. */
    public record CreatedKey(UUID tenantId, UUID keyId, String apiKey, String keyPrefix) {}

    public record TenantView(UUID id, String name, int requestsPerMinute, Long dailyTokenBudget, long activeKeys) {}
}
