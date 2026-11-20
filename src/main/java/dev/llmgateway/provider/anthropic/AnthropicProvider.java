package dev.llmgateway.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.codec.DecodingException;
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
import dev.llmgateway.provider.anthropic.AnthropicModels.MessagesResponse;

/** Adapter for Anthropic's Messages API (https://docs.claude.com). */
public class AnthropicProvider implements LlmProvider {

    static final String API_VERSION = "2023-06-01";

    private final String name;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final boolean configured;
    private final int defaultMaxTokens;

    public AnthropicProvider(String name, ProviderSettings settings, WebClient.Builder builder,
                             ObjectMapper mapper, int defaultMaxTokens) {
        this.name = name;
        this.mapper = mapper;
        this.defaultMaxTokens = defaultMaxTokens;
        this.configured = StringUtils.hasText(settings.apiKey()) && StringUtils.hasText(settings.baseUrl());
        this.client = builder
                .baseUrl(StringUtils.hasText(settings.baseUrl()) ? settings.baseUrl() : "https://api.anthropic.com")
                .defaultHeader("x-api-key", settings.apiKey() == null ? "" : settings.apiKey())
                .defaultHeader("anthropic-version", API_VERSION)
                .build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ProviderType type() {
        return ProviderType.ANTHROPIC;
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        return Mono.fromCallable(() -> AnthropicMapper.toAnthropic(request, defaultMaxTokens, false))
                .flatMap(body -> client.post()
                        .uri("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, UpstreamErrors.handler(name))
                        .bodyToMono(MessagesResponse.class))
                .onErrorMap(DecodingException.class, e -> UpstreamErrors.malformed(name, e))
                .map(AnthropicMapper::toOpenAi);
    }

    @Override
    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
        return Flux.defer(() -> {
            AnthropicMapper.StreamTranslator translator = new AnthropicMapper.StreamTranslator(name);
            return client.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(AnthropicMapper.toAnthropic(request, defaultMaxTokens, true))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, UpstreamErrors.handler(name))
                    .bodyToFlux(UpstreamErrors.SSE_STRING)
                    .mapNotNull(ServerSentEvent::data)
                    .filter(data -> !data.isBlank())
                    .map(this::parse)
                    .concatMapIterable(translator::translate);
        });
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw UpstreamErrors.malformed(name, e);
        }
    }
}
