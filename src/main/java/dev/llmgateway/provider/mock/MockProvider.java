package dev.llmgateway.provider.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import dev.llmgateway.api.openai.ChatCompletionChunk;
import dev.llmgateway.api.openai.ChatCompletionChunk.Delta;
import dev.llmgateway.api.openai.ChatCompletionRequest;
import dev.llmgateway.api.openai.ChatCompletionResponse;
import dev.llmgateway.api.openai.ChatMessage;
import dev.llmgateway.api.openai.Usage;
import dev.llmgateway.config.GatewayProperties.ProviderSettings;
import dev.llmgateway.config.GatewayProperties.ProviderType;
import dev.llmgateway.core.TokenEstimator;
import dev.llmgateway.provider.LlmProvider;
import dev.llmgateway.provider.UpstreamException;

/**
 * A fake LLM that streams a canned answer at a configurable speed and fails at a configurable rate.
 * It makes the gateway demoable with zero API keys and is what the load tests and chaos route use.
 */
public class MockProvider implements LlmProvider {

    private static final String ANSWER =
            "This is a simulated response from the gateway's mock provider. It lets you exercise routing, "
                    + "streaming, failover, rate limiting and cost tracking without calling a paid model. "
                    + "You said: ";
    private static final int ECHO_LIMIT = 200;

    private final String name;
    private final double failureRate;
    private final Duration perToken;

    public MockProvider(String name, ProviderSettings settings) {
        this.name = name;
        this.failureRate = settings.failureRate();
        this.perToken = Duration.ofNanos(1_000_000_000L / settings.tokensPerSecond());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ProviderType type() {
        return ProviderType.MOCK;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        return Mono.defer(() -> {
            if (shouldFail()) {
                return Mono.error(simulatedFailure());
            }
            String text = answerFor(request);
            int completionTokens = text.split(" ").length;
            ChatCompletionResponse response = new ChatCompletionResponse(
                    newId(), ChatCompletionResponse.OBJECT, Instant.now().getEpochSecond(), request.model(),
                    List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant(text), "stop")),
                    Usage.of(TokenEstimator.estimatePrompt(request.messages()), completionTokens));
            return Mono.delay(perToken.multipliedBy(completionTokens)).thenReturn(response);
        });
    }

    @Override
    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
        return Flux.defer(() -> {
            if (shouldFail()) {
                return Flux.error(simulatedFailure());
            }
            String id = newId();
            long created = Instant.now().getEpochSecond();
            String model = request.model();
            String[] words = answerFor(request).split(" ");
            Usage usage = Usage.of(TokenEstimator.estimatePrompt(request.messages()), words.length);

            Flux<ChatCompletionChunk> tokens = Flux.fromArray(words)
                    .index()
                    .map(indexed -> ChatCompletionChunk.delta(id, model, created,
                            new Delta(indexed.getT1() == 0 ? "assistant" : null,
                                    indexed.getT1() == 0 ? indexed.getT2() : " " + indexed.getT2()),
                            null))
                    .delayElements(perToken);

            return Flux.concat(
                    tokens,
                    Flux.just(
                            ChatCompletionChunk.delta(id, model, created, new Delta(null, null), "stop"),
                            ChatCompletionChunk.ofUsage(id, model, created, usage)));
        });
    }

    private boolean shouldFail() {
        return failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate;
    }

    private UpstreamException simulatedFailure() {
        return new UpstreamException(name, 503, "Simulated outage (failure-rate=" + failureRate + ")");
    }

    private static String answerFor(ChatCompletionRequest request) {
        String lastUserMessage = request.messages().stream()
                .filter(message -> "user".equals(message.role()) && message.content() != null)
                .reduce((first, second) -> second)
                .map(ChatMessage::content)
                .orElse("(nothing)");
        if (lastUserMessage.length() > ECHO_LIMIT) {
            lastUserMessage = lastUserMessage.substring(0, ECHO_LIMIT) + "...";
        }
        return ANSWER + lastUserMessage.replaceAll("\\s+", " ").trim();
    }

    private static String newId() {
        return "chatcmpl-mock-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
