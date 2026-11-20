package dev.llmgateway.core;

import dev.llmgateway.api.openai.ChatCompletionResponse;

/** A non-streaming result plus which provider served it and how many fallbacks it took. */
public record GatewayResult(ChatCompletionResponse response, String provider, int fallbacks) {
}
