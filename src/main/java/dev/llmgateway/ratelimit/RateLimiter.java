package dev.llmgateway.ratelimit;

import java.time.Duration;
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
 * Per-tenant requests-per-minute limit using a token bucket in Redis (see scripts/token_bucket.lua).
 * Allows short bursts up to the per-minute limit, then refills smoothly.
 * <p>
 * Fails open: if Redis is unreachable, requests are allowed and a metric is incremented.
 * For an internal gateway, availability matters more than strict enforcement; flip this for paid public APIs.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final RedisScript<List<Long>> SCRIPT = RedisScripts.load("scripts/token_bucket.lua");

    private final ReactiveStringRedisTemplate redis;
    private final GatewayMetrics metrics;

    public RateLimiter(ReactiveStringRedisTemplate redis, GatewayMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    public Mono<Void> acquire(ClientIdentity client) {
        int capacity = client.requestsPerMinute();
        double refillPerSecond = capacity / 60.0;
        List<String> keys = List.of("ratelimit:" + client.tenantId());
        List<String> args = List.of(String.valueOf(capacity), String.valueOf(refillPerSecond), "1");

        return RedisScripts.run(redis, SCRIPT, keys, args)
                .flatMap(result -> {
                    if (result.get(0) == 1L) {
                        return Mono.<Void>empty();
                    }
                    metrics.rejected("rate_limit");
                    return Mono.<Void>error(GatewayException.rateLimited(Duration.ofMillis(result.get(2))));
                })
                .onErrorResume(error -> !(error instanceof GatewayException), error -> {
                    log.warn("Rate limiter unavailable, failing open: {}", error.toString());
                    metrics.failOpen("rate_limiter");
                    return Mono.empty();
                });
    }
}
