package dev.llmgateway.api.openai;

import java.util.List;

public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage) {

    public static final String OBJECT = "chat.completion";

    public record Choice(int index, ChatMessage message, String finishReason) {}

    /** Total characters of assistant text in the response; used to estimate tokens when usage is missing. */
    public int contentChars() {
        if (choices == null) {
            return 0;
        }
        int chars = 0;
        for (Choice choice : choices) {
            if (choice.message() != null && choice.message().content() != null) {
                chars += choice.message().content().length();
            }
        }
        return chars;
    }
}
