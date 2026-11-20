package dev.llmgateway.provider.anthropic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import dev.llmgateway.api.openai.ChatCompletionChunk;
import dev.llmgateway.api.openai.ChatCompletionChunk.Delta;
import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ChatCompletionResponse;
import dev.llmgateway.api.openai.ChatMessage;
import dev.llmgateway.api.openai.Usage;
import dev.llmgateway.core.GatewayException;
import dev.llmgateway.provider.UpstreamException;
import dev.llmgateway.provider.anthropic.AnthropicModels.ContentBlock;
import dev.llmgateway.provider.anthropic.AnthropicModels.Message;
import dev.llmgateway.provider.anthropic.AnthropicModels.MessagesRequest;
import dev.llmgateway.provider.anthropic.AnthropicModels.MessagesResponse;

/**
 * Translates between OpenAI chat-completion shapes and Anthropic's Messages API.
 * Pure functions (plus a small per-stream state machine), so it is fully unit-testable without HTTP.
 */
public final class AnthropicMapper {

    /** OpenAI accepts temperature 0-2; Anthropic accepts 0-1. */
    static final double MAX_TEMPERATURE = 1.0;

    private AnthropicMapper() {
    }

    public static MessagesRequest toAnthropic(ChatCompletionRequest request, int defaultMaxTokens, boolean stream) {
        StringBuilder system = new StringBuilder();
        List<Message> messages = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            String role = message.role() == null ? "user" : message.role();
            String content = message.content() == null ? "" : message.content();
            switch (role) {
                // Anthropic takes the system prompt as a top-level field rather than a message.
                case "system", "developer" -> {
                    if (!system.isEmpty()) {
                        system.append("\n\n");
                    }
                    system.append(content);
                }
                case "user", "assistant" -> messages.add(new Message(role, content));
                default -> throw GatewayException.badRequest(
                        "Message role '" + role + "' is not supported by the Anthropic adapter yet.");
            }
        }
        if (messages.isEmpty()) {
            throw GatewayException.badRequest("At least one user or assistant message is required.");
        }
        Double temperature = request.temperature() == null ? null : Math.min(request.temperature(), MAX_TEMPERATURE);
        Integer maxTokens = request.maxOutputTokens() != null ? request.maxOutputTokens() : defaultMaxTokens;
        return new MessagesRequest(
                request.model(),
                maxTokens,
                system.isEmpty() ? null : system.toString(),
                messages,
                temperature,
                request.topP(),
                request.stop(),
                stream ? Boolean.TRUE : null);
    }

    public static ChatCompletionResponse toOpenAi(MessagesResponse response) {
        String text = response.content() == null ? "" : response.content().stream()
                .filter(block -> "text".equals(block.type()) && block.text() != null)
                .map(ContentBlock::text)
                .collect(Collectors.joining());
        Usage usage = response.usage() == null ? null : Usage.of(
                orZero(response.usage().inputTokens()),
                orZero(response.usage().outputTokens()));
        return new ChatCompletionResponse(
                response.id(),
                ChatCompletionResponse.OBJECT,
                Instant.now().getEpochSecond(),
                response.model(),
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant(text), finishReason(response.stopReason()))),
                usage);
    }

    static String finishReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            default -> "stop"; // end_turn, stop_sequence, pause_turn
        };
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Converts Anthropic's typed stream events (message_start, content_block_delta, message_delta, ...)
     * into OpenAI chunks. One instance per stream: it remembers the message id, model and token counts.
     */
    public static final class StreamTranslator {

        private final String provider;
        private final long created = Instant.now().getEpochSecond();
        private String id = "msg_unknown";
        private String model;
        private int inputTokens;
        private int outputTokens;

        public StreamTranslator(String provider) {
            this.provider = provider;
        }

        public List<ChatCompletionChunk> translate(JsonNode event) {
            String type = event.path("type").asText();
            return switch (type) {
                case "message_start" -> {
                    JsonNode message = event.path("message");
                    id = message.path("id").asText(id);
                    model = message.path("model").asText(model);
                    inputTokens = message.path("usage").path("input_tokens").asInt(0);
                    outputTokens = message.path("usage").path("output_tokens").asInt(0);
                    yield List.of(ChatCompletionChunk.delta(id, model, created, new Delta("assistant", ""), null));
                }
                case "content_block_delta" -> {
                    JsonNode delta = event.path("delta");
                    if ("text_delta".equals(delta.path("type").asText())) {
                        yield List.of(ChatCompletionChunk.delta(id, model, created,
                                new Delta(null, delta.path("text").asText("")), null));
                    }
                    yield List.of(); // tool-use / thinking deltas are not forwarded yet
                }
                case "message_delta" -> {
                    JsonNode usage = event.path("usage");
                    if (usage.has("output_tokens")) {
                        outputTokens = usage.get("output_tokens").asInt();
                    }
                    if (usage.has("input_tokens")) {
                        inputTokens = usage.get("input_tokens").asInt();
                    }
                    String finish = finishReason(event.path("delta").path("stop_reason").asText(null));
                    yield List.of(
                            ChatCompletionChunk.delta(id, model, created, new Delta(null, null), finish),
                            ChatCompletionChunk.ofUsage(id, model, created, Usage.of(inputTokens, outputTokens)));
                }
                case "error" -> {
                    JsonNode error = event.path("error");
                    throw new UpstreamException(provider, statusFor(error.path("type").asText()),
                            error.path("message").asText("Anthropic stream error"));
                }
                default -> List.of(); // ping, content_block_start, content_block_stop, message_stop
            };
        }

        private static int statusFor(String errorType) {
            return switch (errorType) {
                case "overloaded_error" -> 529;
                case "rate_limit_error" -> 429;
                case "invalid_request_error" -> 400;
                case "authentication_error" -> 401;
                case "permission_error" -> 403;
                case "not_found_error" -> 404;
                default -> 500;
            };
        }
    }
}
