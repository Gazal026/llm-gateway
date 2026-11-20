package dev.llmgateway.api;

import java.security.Principal;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ModelList;
import dev.llmgateway.api.openai.OpenAiError;
import dev.llmgateway.auth.ApiKeyAuthentication;
import dev.llmgateway.auth.ClientIdentity;
import dev.llmgateway.core.GatewayException;
import dev.llmgateway.core.GatewayService;
import dev.llmgateway.core.RequestContext;
import dev.llmgateway.routing.ModelRouter;

/** HTTP layer for the OpenAI-compatible API. All business logic lives in {@link GatewayService}. */
@Component
public class ChatHandler {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String PROVIDER_HEADER = "X-Gateway-Provider";
    public static final String FALLBACKS_HEADER = "X-Gateway-Fallbacks";
    private static final int MAX_REQUEST_ID_LENGTH = 100;

    private final GatewayService gateway;
    private final ModelRouter router;
    private final ObjectMapper mapper;

    public ChatHandler(GatewayService gateway, ModelRouter router, ObjectMapper mapper) {
        this.gateway = gateway;
        this.router = router;
        this.mapper = mapper;
    }

    public Mono<ServerResponse> chatCompletions(ServerRequest request) {
        String requestId = requestId(request);
        Mono<ClientIdentity> client = request.principal().map(ChatHandler::client);
        Mono<ChatCompletionRequest> body = request.bodyToMono(ChatCompletionRequest.class)
                .switchIfEmpty(Mono.error(() -> GatewayException.badRequest("Request body is required.")));

        return Mono.zip(client, body)
                .flatMap(tuple -> {
                    ChatCompletionRequest completionRequest = tuple.getT2();
                    RequestValidator.validate(completionRequest);
                    RequestContext ctx = new RequestContext(requestId, tuple.getT1(), System.nanoTime());
                    return completionRequest.streaming()
                            ? streamResponse(ctx, completionRequest)
                            : jsonResponse(ctx, completionRequest);
                })
                .onErrorResume(error -> errorResponse(error, requestId));
    }

    public Mono<ServerResponse> models(ServerRequest request) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ModelList.of(router.aliases()));
    }

    private Mono<ServerResponse> jsonResponse(RequestContext ctx, ChatCompletionRequest request) {
        return gateway.complete(ctx, request)
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(REQUEST_ID_HEADER, ctx.requestId())
                        .header(PROVIDER_HEADER, result.provider())
                        .header(FALLBACKS_HEADER, String.valueOf(result.fallbacks()))
                        .bodyValue(result.response()));
    }

    private Mono<ServerResponse> streamResponse(RequestContext ctx, ChatCompletionRequest request) {
        return gateway.stream(ctx, request).flatMap(chunks -> {
            Flux<ServerSentEvent<String>> events = chunks
                    .map(chunk -> ServerSentEvent.builder(toJson(chunk)).build())
                    .concatWith(Mono.fromSupplier(() -> ServerSentEvent.builder("[DONE]").build()))
                    // Headers are already sent, so mid-stream failures are reported as a final error event.
                    .onErrorResume(error -> Mono.just(ServerSentEvent.builder(toJson(errorBody(error))).build()));
            return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(REQUEST_ID_HEADER, ctx.requestId())
                    .body(BodyInserters.fromServerSentEvents(events));
        });
    }

    private Mono<ServerResponse> errorResponse(Throwable error, String requestId) {
        GatewayException gatewayError = GatewayException.from(error);
        ServerResponse.BodyBuilder builder = ServerResponse.status(gatewayError.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(REQUEST_ID_HEADER, requestId);
        if (gatewayError.retryAfter() != null) {
            builder.header("Retry-After", String.valueOf(gatewayError.retryAfterSeconds()));
        }
        return builder.bodyValue(errorBody(gatewayError));
    }

    private static OpenAiError errorBody(Throwable error) {
        GatewayException gatewayError = GatewayException.from(error);
        return OpenAiError.of(gatewayError.getMessage(), gatewayError.type(), gatewayError.code());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private static ClientIdentity client(Principal principal) {
        if (principal instanceof ApiKeyAuthentication authentication
                && authentication.getPrincipal() instanceof ClientIdentity identity) {
            return identity;
        }
        throw new IllegalStateException("Request reached the chat handler without a client identity");
    }

    /** Honors a caller-supplied request id (useful for tracing across services) or generates one. */
    private static String requestId(ServerRequest request) {
        String supplied = request.headers().firstHeader(REQUEST_ID_HEADER);
        if (supplied != null && !supplied.isBlank() && supplied.length() <= MAX_REQUEST_ID_LENGTH) {
            return supplied;
        }
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
