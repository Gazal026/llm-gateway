package dev.llmgateway.provider.anthropic;

import java.util.List;

/** Request/response shapes for Anthropic's Messages API (only the fields the gateway uses). */
public final class AnthropicModels {

    private AnthropicModels() {
    }

    public record MessagesRequest(
            String model,
            Integer maxTokens,
            String system,
            List<Message> messages,
            Double temperature,
            Double topP,
            List<String> stopSequences,
            Boolean stream) {}

    public record Message(String role, String content) {}

    public record MessagesResponse(
            String id,
            String model,
            List<ContentBlock> content,
            String stopReason,
            TokenUsage usage) {}

    public record ContentBlock(String type, String text) {}

    public record TokenUsage(Integer inputTokens, Integer outputTokens) {}
}
