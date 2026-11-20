package dev.llmgateway.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import dev.llmgateway.api.openai.ChatCompletionChunk;
import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ChatCompletionResponse;
import dev.llmgateway.api.openai.ChatMessage;
import dev.llmgateway.core.GatewayException;
import dev.llmgateway.provider.UpstreamException;
import dev.llmgateway.provider.anthropic.AnthropicModels.ContentBlock;
import dev.llmgateway.provider.anthropic.AnthropicModels.MessagesRequest;
import dev.llmgateway.provider.anthropic.AnthropicModels.MessagesResponse;
import dev.llmgateway.provider.anthropic.AnthropicModels.TokenUsage;

class AnthropicMapperTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void systemMessagesBecomeTopLevelSystemPromptAndTemperatureIsClamped() {
        ChatCompletionRequest request = request(List.of(
                ChatMessage.system("Be brief."),
                ChatMessage.system("Answer in English."),
                ChatMessage.user("Hi"),
                ChatMessage.assistant("Hello!"),
                ChatMessage.user("How are you?")), 1.7, null);

        MessagesRequest mapped = AnthropicMapper.toAnthropic(request, 1024, false);

        assertThat(mapped.system()).isEqualTo("Be brief.\n\nAnswer in English.");
        assertThat(mapped.messages()).extracting(AnthropicModels.Message::role)
                .containsExactly("user", "assistant", "user");
        assertThat(mapped.temperature()).isEqualTo(1.0);
        assertThat(mapped.maxTokens()).isEqualTo(1024);
        assertThat(mapped.stream()).isNull();
    }

    @Test
    void clientMaxTokensWinsOverDefault() {
        MessagesRequest mapped = AnthropicMapper.toAnthropic(
                request(List.of(ChatMessage.user("Hi")), null, 50), 1024, true);

        assertThat(mapped.maxTokens()).isEqualTo(50);
        assertThat(mapped.stream()).isTrue();
    }

    @Test
    void unsupportedRoleIsABadRequest() {
        ChatCompletionRequest request = request(List.of(new ChatMessage("tool", "{}", null)), null, null);

        assertThatThrownBy(() -> AnthropicMapper.toAnthropic(request, 1024, false))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("tool");
    }

    @Test
    void responseIsMappedToOpenAiShape() {
        MessagesResponse response = new MessagesResponse("msg_1", "claude-haiku-5-5",
                List.of(new ContentBlock("text", "Hello "), new ContentBlock("text", "there")),
                "max_tokens", new TokenUsage(12, 7));

        ChatCompletionResponse mapped = AnthropicMapper.toOpenAi(response);

        assertThat(mapped.choices()).singleElement().satisfies(choice -> {
            assertThat(choice.message().content()).isEqualTo("Hello there");
            assertThat(choice.finishReason()).isEqualTo("length");
        });
        assertThat(mapped.usage().promptTokens()).isEqualTo(12);
        assertThat(mapped.usage().completionTokens()).isEqualTo(7);
        assertThat(mapped.usage().totalTokens()).isEqualTo(19);
    }

    @Test
    void streamEventsAreTranslatedIntoOpenAiChunksWithUsage() throws Exception {
        AnthropicMapper.StreamTranslator translator = new AnthropicMapper.StreamTranslator("anthropic");
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        for (String event : List.of(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-haiku-5-5\",\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"ping\"}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":15}}",
                "{\"type\":\"message_stop\"}")) {
            chunks.addAll(translator.translate(json.readTree(event)));
        }

        assertThat(chunks).hasSize(5);
        assertThat(chunks.get(0).choices().get(0).delta().role()).isEqualTo("assistant");
        assertThat(chunks.get(1).choices().get(0).delta().content()).isEqualTo("Hello");
        assertThat(chunks.get(2).choices().get(0).delta().content()).isEqualTo(" world");
        assertThat(chunks.get(3).choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(chunks.get(4).carriesOnlyUsage()).isTrue();
        assertThat(chunks.get(4).usage().promptTokens()).isEqualTo(25);
        assertThat(chunks.get(4).usage().completionTokens()).isEqualTo(15);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.id()).isEqualTo("msg_1"));
    }

    @Test
    void overloadedErrorEventBecomesRetryableUpstreamError() throws Exception {
        AnthropicMapper.StreamTranslator translator = new AnthropicMapper.StreamTranslator("anthropic");
        JsonNode event = json.readTree(
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");

        assertThatThrownBy(() -> translator.translate(event))
                .isInstanceOfSatisfying(UpstreamException.class, error -> {
                    assertThat(error.status()).isEqualTo(529);
                    assertThat(error.retryable()).isTrue();
                });
    }

    private static ChatCompletionRequest request(List<ChatMessage> messages, Double temperature, Integer maxTokens) {
        return new ChatCompletionRequest("claude-haiku-5-5", messages, null, null, temperature, null, maxTokens,
                null, null, null);
    }
}
