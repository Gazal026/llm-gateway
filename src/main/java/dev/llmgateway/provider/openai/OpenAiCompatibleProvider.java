package dev.llmgateway.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import dev.llmgateway.api.openai.ChatCompletionChunk;
import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ChatCompletionResponse;
import dev.llmgateway.config.GatewayProperties.ProviderSettings;
import dev.llmgateway.config.GatewayProperties.ProviderType;
import dev.llmgateway.provider.LlmProvider;
import dev.llmgateway.provider.UpstreamErrors;

/**
 * Any API that speaks OpenAI's /chat/completions: OpenAI itself, Ollama, vLLM, Azure-compatible proxies, etc.
 * Since the gateway's own API is OpenAI-shaped, this adapter is mostly a pass-through.
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final String DONE = "[DONE]";

    private final String name;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final boolean configured;

    public OpenAiCompatibleProvider(String name, ProviderSettings settings, WebClient.Builder builder, ObjectMapper mapper) {
        this.name = name;
        this.mapper = mapper;
        this.configured = StringUtils.hasText(settings.baseUrl());
        WebClient.Builder configuredBuilder = builder.baseUrl(configured ? settings.baseUrl() : "http://unconfigured.invalid");
        if (StringUtils.hasText(settings.apiKey())) {
            configuredBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey());
        }
        this.client = configuredBuilder.build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ProviderType type() {
        return ProviderType.OPENAI;
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        return client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request.forUpstream(request.model(), false))
                .retrieve()
                .onStatus(HttpStatusCode::isError, UpstreamErrors.handler(name))
                .bodyToMono(ChatCompletionResponse.class)
                .onErrorMap(DecodingException.class, e -> UpstreamErrors.malformed(name, e));
    }

    @Override
    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
        // Always ask upstream for usage so billing is exact; the gateway decides whether to forward it.
        return client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request.forUpstream(request.model(), true))
                .retrieve()
                .onStatus(HttpStatusCode::isError, UpstreamErrors.handler(name))
                .bodyToFlux(UpstreamErrors.SSE_STRING)
                .mapNotNull(ServerSentEvent::data)
                .map(String::trim)
                .filter(data -> !data.isEmpty())
                .takeWhile(data -> !DONE.equals(data))
                .map(this::parseChunk);
    }

    private ChatCompletionChunk parseChunk(String json) {
        try {
            return mapper.readValue(json, ChatCompletionChunk.class);
        } catch (JsonProcessingException e) {
            throw UpstreamErrors.malformed(name, e);
        }
    }
}
