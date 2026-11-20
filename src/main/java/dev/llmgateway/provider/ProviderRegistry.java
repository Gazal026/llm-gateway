package dev.llmgateway.provider;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ProviderRegistry {

    private final Map<String, LlmProvider> providers;

    public ProviderRegistry(Map<String, LlmProvider> providers) {
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
    }

    public Optional<LlmProvider> find(String name) {
        return Optional.ofNullable(providers.get(name));
    }

    public Collection<LlmProvider> all() {
        return providers.values();
    }
}
