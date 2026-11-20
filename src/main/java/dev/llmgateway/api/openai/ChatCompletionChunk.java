package dev.llmgateway.api.openai;

import java.util.List;

/** One server-sent event in an OpenAI-style streaming response. */
public record ChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        List<ChunkChoice> choices,
        Usage usage) {

    public static final String OBJECT = "chat.completion.chunk";

    public record ChunkChoice(int index, Delta delta, String finishReason) {}

    public record Delta(String role, String content) {}

    public static ChatCompletionChunk delta(String id, String model, long created, Delta delta, String finishReason) {
        return new ChatCompletionChunk(id, OBJECT, created, model,
                List.of(new ChunkChoice(0, delta, finishReason)), null);
    }

    /** The final chunk OpenAI sends when stream_options.include_usage is true: no choices, just usage. */
    public static ChatCompletionChunk ofUsage(String id, String model, long created, Usage usage) {
        return new ChatCompletionChunk(id, OBJECT, created, model, List.of(), usage);
    }

    public boolean carriesOnlyUsage() {
        return usage != null && (choices == null || choices.isEmpty());
    }

    /** Characters of assistant text carried by this chunk. */
    public int contentChars() {
        if (choices == null) {
            return 0;
        }
        int chars = 0;
        for (ChunkChoice choice : choices) {
            if (choice.delta() != null && choice.delta().content() != null) {
                chars += choice.delta().content().length();
            }
        }
        return chars;
    }
}
