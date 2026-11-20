package dev.llmgateway.api.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * The subset of the OpenAI chat-completions request that the gateway understands.
 * Unknown fields are ignored (Spring Boot's default), so existing OpenAI clients keep working.
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Boolean stream,
        StreamOptions streamOptions,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Integer maxCompletionTokens,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> stop,
        String user) {

    /** True when the client asked for a server-sent-event stream. */
    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }

    /** True when a streaming client asked for the final usage chunk (OpenAI's stream_options.include_usage). */
    public boolean includeUsage() {
        return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
    }

    /** The output-token cap the client asked for, if any. Newer OpenAI clients send max_completion_tokens. */
    public Integer maxOutputTokens() {
        return maxCompletionTokens != null ? maxCompletionTokens : maxTokens;
    }

    /** Copy of this request aimed at a specific upstream model, with streaming flags set for the upstream call. */
    public ChatCompletionRequest forUpstream(String upstreamModel, boolean upstreamStream) {
        return new ChatCompletionRequest(
                upstreamModel,
                messages,
                upstreamStream ? Boolean.TRUE : null,
                upstreamStream ? new StreamOptions(Boolean.TRUE) : null,
                temperature,
                topP,
                maxTokens,
                maxCompletionTokens,
                stop,
                user);
    }

    public record StreamOptions(Boolean includeUsage) {}
}
