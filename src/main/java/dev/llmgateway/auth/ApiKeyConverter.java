package dev.llmgateway.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reads the key from {@code Authorization: Bearer <key>} (OpenAI SDKs) or {@code x-api-key: <key>} (Anthropic style).
 * Returns empty when neither is present, so unauthenticated requests reach the entry point and get a 401.
 */
public class ApiKeyConverter implements ServerAuthenticationConverter {

    private static final String BEARER = "Bearer ";

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String key = authorization.substring(BEARER.length()).trim();
            if (!key.isEmpty()) {
                return Mono.just(ApiKeyAuthentication.unauthenticated(key));
            }
        }
        String apiKey = headers.getFirst("x-api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            return Mono.just(ApiKeyAuthentication.unauthenticated(apiKey.trim()));
        }
        return Mono.empty();
    }
}
