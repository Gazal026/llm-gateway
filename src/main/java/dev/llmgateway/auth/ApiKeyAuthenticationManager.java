package dev.llmgateway.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import dev.llmgateway.config.GatewayProperties;

/** Resolves a raw key to either the admin or a tenant's {@link ClientIdentity}. */
@Component
public class ApiKeyAuthenticationManager implements ReactiveAuthenticationManager {

    private final TenantService tenants;
    private final String adminKey;

    public ApiKeyAuthenticationManager(TenantService tenants, GatewayProperties properties) {
        this.tenants = tenants;
        this.adminKey = properties.adminKey();
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String rawKey = (String) authentication.getCredentials();
        if (StringUtils.hasText(adminKey) && ApiKeys.constantTimeEquals(rawKey, adminKey)) {
            return Mono.just(ApiKeyAuthentication.admin());
        }
        return tenants.findByApiKey(rawKey)
                .<Authentication>map(ApiKeyAuthentication::client)
                .switchIfEmpty(Mono.error(() -> new BadCredentialsException("Invalid API key")));
    }
}
