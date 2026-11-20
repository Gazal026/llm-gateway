package dev.llmgateway.ratelimit;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

final class RedisScripts {

    private RedisScripts() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static RedisScript<List<Long>> load(String path) {
        return (RedisScript<List<Long>>) (RedisScript) RedisScript.of(new ClassPathResource(path), List.class);
    }

    /** Runs a script that returns an array of integers and collects the reply into one list. */
    static Mono<List<Long>> run(ReactiveStringRedisTemplate redis, RedisScript<List<Long>> script,
                                List<String> keys, List<String> args) {
        return redis.execute(script, keys, args)
                .<List<Long>>reduceWith(ArrayList::new, (all, part) -> {
                    all.addAll(part);
                    return all;
                });
    }
}
