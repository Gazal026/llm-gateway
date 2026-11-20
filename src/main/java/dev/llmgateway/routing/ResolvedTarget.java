package dev.llmgateway.routing;

import dev.llmgateway.provider.LlmProvider;

/** One concrete place a request can go: a provider plus the model name that provider understands. */
public record ResolvedTarget(LlmProvider provider, String model) {

    public String providerName() {
        return provider.name();
    }
}
