package dev.llmgateway.core;

import java.util.List;

import dev.llmgateway.api.openai.ChatMessage;

/**
 * Cheap token estimates (~4 characters per token for English text).
 * Used to reserve budget before a call and to fill in usage when a provider doesn't report it.
 * Real counts from the provider always win when available.
 */
public final class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int PER_MESSAGE_OVERHEAD = 4;
    private static final int PRIMING_TOKENS = 3;

    private TokenEstimator() {
    }

    public static int estimatePrompt(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int tokens = PRIMING_TOKENS;
        for (ChatMessage message : messages) {
            tokens += PER_MESSAGE_OVERHEAD;
            if (message.content() != null) {
                tokens += fromChars(message.content().length());
            }
        }
        return tokens;
    }

    public static int fromChars(int chars) {
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }
}
