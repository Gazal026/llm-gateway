package dev.llmgateway.api.openai;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * A chat message. Content may arrive as a plain string or as OpenAI's array of content parts;
 * the deserializer flattens text parts into one string.
 */
public record ChatMessage(
        String role,
        @JsonDeserialize(using = MessageContentDeserializer.class) String content,
        String name) {

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null);
    }
}
