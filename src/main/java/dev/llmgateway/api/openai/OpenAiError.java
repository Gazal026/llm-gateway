package dev.llmgateway.api.openai;

/** OpenAI's error envelope, so client SDKs surface gateway errors the same way as provider errors. */
public record OpenAiError(ErrorBody error) {

    public record ErrorBody(String message, String type, String code) {}

    public static OpenAiError of(String message, String type, String code) {
        return new OpenAiError(new ErrorBody(message, type, code));
    }
}
