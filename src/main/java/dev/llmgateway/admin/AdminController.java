package dev.llmgateway.admin;

import java.util.List;
import java.util.UUID;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import dev.llmgateway.auth.TenantService;
import dev.llmgateway.auth.TenantService.CreatedKey;
import dev.llmgateway.auth.TenantService.TenantView;
import dev.llmgateway.core.GatewayException;
import dev.llmgateway.provider.LlmProvider;
import dev.llmgateway.provider.ProviderRegistry;
import dev.llmgateway.usage.UsageQueries;
import dev.llmgateway.usage.UsageQueries.UsageSummary;

/** Admin API (requires the admin key). This is the backend for the Angular dashboard in the next milestone. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final int MAX_HOURS = 24 * 90;

    private final TenantService tenants;
    private final UsageQueries usage;
    private final ProviderRegistry providers;
    private final CircuitBreakerRegistry circuitBreakers;

    public AdminController(TenantService tenants, UsageQueries usage, ProviderRegistry providers,
                           CircuitBreakerRegistry circuitBreakers) {
        this.tenants = tenants;
        this.usage = usage;
        this.providers = providers;
        this.circuitBreakers = circuitBreakers;
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CreatedKey> createTenant(@RequestBody CreateTenantRequest request) {
        return tenants.createTenant(request.name(), request.requestsPerMinute(), request.dailyTokenBudget());
    }

    @GetMapping("/tenants")
    public Flux<TenantView> listTenants() {
        return tenants.listTenants();
    }

    @PostMapping("/tenants/{tenantId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CreatedKey> createKey(@PathVariable UUID tenantId) {
        return tenants.createKey(tenantId);
    }

    @DeleteMapping("/keys/{keyId}")
    public Mono<ResponseEntity<Void>> revokeKey(@PathVariable UUID keyId) {
        return tenants.revokeKey(keyId)
                .map(revoked -> revoked
                        ? ResponseEntity.noContent().<Void>build()
                        : ResponseEntity.notFound().<Void>build());
    }

    @GetMapping("/usage/summary")
    public Flux<UsageSummary> usageSummary(@RequestParam(defaultValue = "24") int hours) {
        if (hours <= 0 || hours > MAX_HOURS) {
            return Flux.error(GatewayException.badRequest("'hours' must be between 1 and " + MAX_HOURS + "."));
        }
        return usage.summary(hours);
    }

    @GetMapping("/providers")
    public List<ProviderStatus> providers() {
        return providers.all().stream().map(this::status).toList();
    }

    private ProviderStatus status(LlmProvider provider) {
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(provider.name());
        CircuitBreaker.Metrics metrics = breaker.getMetrics();
        return new ProviderStatus(
                provider.name(),
                provider.type().name().toLowerCase(),
                provider.isConfigured(),
                breaker.getState().name(),
                metrics.getFailureRate(),
                metrics.getNumberOfBufferedCalls());
    }

    public record CreateTenantRequest(String name, Integer requestsPerMinute, Long dailyTokenBudget) {}

    /** failureRate is -1 until the breaker has seen its minimum number of calls. */
    public record ProviderStatus(String name, String type, boolean configured, String circuitState,
                                 float failureRate, int bufferedCalls) {}
}
