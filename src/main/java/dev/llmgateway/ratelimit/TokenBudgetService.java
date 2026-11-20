package dev.llmgateway.ratelimit;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import dev.llmgateway.auth.ClientIdentity;
import dev.llmgateway.core.GatewayException;
import dev.llmgateway.core.GatewayMetrics;

/**
 * Daily token budgets per tenant, using reserve-then-reconcile:
 * <ol>
 *   <li>Before the call, atomically reserve (prompt estimate + max output tokens). Reject if it would exceed the budget.</li>
 *   <li>After the call (or when a stream ends or is cancelled), add the difference between actual and reserved.</li>
 * </ol>
 * This prevents many concurrent requests from all passing a check and then overspending together.
 */
@Component
public class TokenBudgetService {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetService.class);
    private static final RedisScript<List<Long>> SCRIPT = RedisScripts.load("scripts/budget_reserve.lua");
    private static final long KEY_TTL_SECONDS = Duration.ofDays(2).toSeconds();

    private final ReactiveStringRedisTemplate redis;
    private final GatewayMetrics metrics;

    public TokenBudgetService(ReactiveStringRedisTemplate redis, GatewayMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    public Mono<Reservation> reserve(ClientIdentity client, int promptEstimate, long tokens) {
        if (client.dailyTokenBudget() == null) {
            return Mono.just(Reservation.unmetered(promptEstimate, tokens));
        }
        String key = "budget:" + client.tenantId() + ":" + LocalDate.now(ZoneOffset.UTC);
        List<String> args = List.of(
                String.valueOf(client.dailyTokenBudget()), String.valueOf(tokens), String.valueOf(KEY_TTL_SECONDS));

        return RedisScripts.run(redis, SCRIPT, List.of(key), args)
                .flatMap(result -> {
                    if (result.get(0) == 1L) {
                        return Mono.just(new Reservation(key, tokens, promptEstimate));
                    }
                    metrics.rejected("token_budget");
                    return Mono.<Reservation>error(GatewayException.budgetExceeded(untilUtcMidnight()));
                })
                .onErrorResume(error -> !(error instanceof GatewayException), error -> {
                    log.warn("Budget store unavailable, failing open: {}", error.toString());
                    metrics.failOpen("token_budget");
                    return Mono.just(Reservation.unmetered(promptEstimate, tokens));
                });
    }

    /** Adjusts the reservation to the real usage. Fire-and-forget: never delays the response. */
    public void reconcile(Reservation reservation, long actualTokens) {
        if (!reservation.metered()) {
            return;
        }
        long delta = actualTokens - reservation.reservedTokens();
        if (delta == 0) {
            return;
        }
        redis.opsForValue().increment(reservation.budgetKey(), delta)
                .subscribe(null, error -> log.warn("Failed to reconcile budget {}: {}", reservation.budgetKey(), error.toString()));
    }

    private static Duration untilUtcMidnight() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC));
    }
}
