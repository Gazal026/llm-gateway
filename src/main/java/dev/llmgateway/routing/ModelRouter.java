package dev.llmgateway.routing;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.llmgateway.config.GatewayProperties;
import dev.llmgateway.config.GatewayProperties.RouteTarget;
import dev.llmgateway.core.GatewayException;
import dev.llmgateway.provider.LlmProvider;
import dev.llmgateway.provider.ProviderRegistry;

/**
 * Turns the model a client asked for into an ordered list of targets to try.
 * <ul>
 *   <li>An alias from {@code gateway.routes} (e.g. "fast") maps to a fallback chain.</li>
 *   <li>"provider/model" (e.g. "openai/gpt-5") goes straight to one provider.</li>
 * </ul>
 * Providers without credentials are dropped, so a route degrades gracefully to whatever is configured.
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final Map<String, List<RouteTarget>> routes;
    private final ProviderRegistry providers;

    public ModelRouter(GatewayProperties properties, ProviderRegistry providers) {
        this.routes = properties.routes();
        this.providers = providers;
        validate();
    }

    public List<ResolvedTarget> resolve(String requestedModel) {
        List<RouteTarget> route = routes.get(requestedModel);
        List<ResolvedTarget> targets;
        if (route != null) {
            targets = route.stream()
                    .map(target -> new ResolvedTarget(providers.find(target.provider()).orElseThrow(), target.model()))
                    .filter(target -> target.provider().isConfigured())
                    .toList();
        } else {
            targets = List.of(direct(requestedModel));
        }
        if (targets.isEmpty()) {
            throw GatewayException.noProviders(requestedModel);
        }
        return targets;
    }

    /** The aliases clients can request, for GET /v1/models. */
    public List<String> aliases() {
        return routes.keySet().stream().sorted().toList();
    }

    private ResolvedTarget direct(String requestedModel) {
        int slash = requestedModel.indexOf('/');
        if (slash <= 0 || slash == requestedModel.length() - 1) {
            throw GatewayException.modelNotFound(requestedModel);
        }
        LlmProvider provider = providers.find(requestedModel.substring(0, slash))
                .orElseThrow(() -> GatewayException.modelNotFound(requestedModel));
        if (!provider.isConfigured()) {
            throw GatewayException.noProviders(requestedModel);
        }
        return new ResolvedTarget(provider, requestedModel.substring(slash + 1));
    }

    /** Fail fast at startup on typos in route configuration. */
    private void validate() {
        routes.forEach((alias, targets) -> {
            if (targets == null || targets.isEmpty()) {
                throw new IllegalStateException("Route '" + alias + "' has no targets");
            }
            for (RouteTarget target : targets) {
                if (providers.find(target.provider()).isEmpty()) {
                    throw new IllegalStateException(
                            "Route '" + alias + "' references unknown provider '" + target.provider() + "'");
                }
            }
            long usable = targets.stream()
                    .filter(target -> providers.find(target.provider()).map(LlmProvider::isConfigured).orElse(false))
                    .count();
            if (usable == 0) {
                log.warn("Route '{}' has no configured providers yet (missing API keys?)", alias);
            }
        });
    }
}
