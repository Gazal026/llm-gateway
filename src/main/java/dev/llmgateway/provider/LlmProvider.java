package dev.llmgateway.provider;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import dev.llmgateway.api.openai.ChatCompletionChunk;
import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ChatCompletionResponse;
import dev.llmgateway.config.GatewayProperties.ProviderType;

/**
 * Adapter for one upstream LLM API. Every adapter speaks the OpenAI shapes on the gateway side,
 * so the rest of the system never sees provider-specific formats.
 */
public interface LlmProvider {

    /** The name used in configuration, routes, metrics and usage records. */
    String name();

    ProviderType type();

    /** False when required settings (such as an API key) are missing; routing then skips this provider. */
    boolean isConfigured();

    /** Non-streaming completion. {@code request.model()} is already the upstream model name. */
    Mono<ChatCompletionResponse> complete(ChatCompletionRequest request);

    /**
     * Streaming completion. Implementations should finish with a usage-only chunk
     * ({@link ChatCompletionChunk#ofUsage}) when the provider reports token usage.
     */
    Flux<ChatCompletionChunk> stream(ChatCompletionRequest request);
}
