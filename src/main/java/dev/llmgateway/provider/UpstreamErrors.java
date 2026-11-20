package dev.llmgateway.provider;

import java.util.function.Function;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/** Helpers shared by the provider adapters. */
public final class UpstreamErrors {

    public static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_STRING =
            new ParameterizedTypeReference<>() {
            };

    private static final int MAX_BODY_CHARS = 500;

    private UpstreamErrors() {
    }

    /** WebClient onStatus handler: turns an error response into an {@link UpstreamException} with a short body. */
    public static Function<ClientResponse, Mono<? extends Throwable>> handler(String provider) {
        return response -> response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new UpstreamException(provider, response.statusCode().value(), truncate(body)));
    }

    /** For responses we could not parse: the provider is misbehaving, so treat it like a 502 from them. */
    public static UpstreamException malformed(String provider, Throwable cause) {
        return new UpstreamException(provider, 502, "Malformed response: " + truncate(cause.getMessage()));
    }

    public static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS) + "...";
    }
}
