package dev.llmgateway.api;

import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ChatMessage;
import dev.llmgateway.core.GatewayException;

/** Cheap checks before any network or Redis work happens. */
final class RequestValidator {

    private static final int MAX_MESSAGES = 1_000;

    private RequestValidator() {
    }

    static void validate(ChatCompletionRequest request) {
        if (request.model() == null || request.model().isBlank()) {
            throw GatewayException.badRequest("'model' is required.");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw GatewayException.badRequest("'messages' must contain at least one message.");
        }
        if (request.messages().size() > MAX_MESSAGES) {
            throw GatewayException.badRequest("'messages' may contain at most " + MAX_MESSAGES + " messages.");
        }
        for (ChatMessage message : request.messages()) {
            if (message == null || message.role() == null || message.role().isBlank()) {
                throw GatewayException.badRequest("Every message needs a 'role'.");
            }
        }
        Integer maxTokens = request.maxOutputTokens();
        if (maxTokens != null && maxTokens <= 0) {
            throw GatewayException.badRequest("'max_tokens' must be positive.");
        }
        if (request.temperature() != null && (request.temperature() < 0 || request.temperature() > 2)) {
            throw GatewayException.badRequest("'temperature' must be between 0 and 2.");
        }
        if (request.topP() != null && (request.topP() < 0 || request.topP() > 1)) {
            throw GatewayException.badRequest("'top_p' must be between 0 and 1.");
        }
    }
}
