package dev.llmgateway.api.openai;

public record Usage(int promptTokens, int completionTokens, int totalTokens) {

    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    public static Usage zero() {
        return new Usage(0, 0, 0);
    }
}
