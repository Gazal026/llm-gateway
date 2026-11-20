package dev.llmgateway.auth;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Spring Security token for API-key auth. Starts unauthenticated (just the raw key) and is replaced by an
 * authenticated token whose principal is either a {@link ClientIdentity} (ROLE_CLIENT) or the admin (ROLE_ADMIN).
 */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    public static final String ROLE_CLIENT = "CLIENT";
    public static final String ROLE_ADMIN = "ADMIN";

    private final Object principal;
    private final String rawKey;

    private ApiKeyAuthentication(String rawKey) {
        super(AuthorityUtils.NO_AUTHORITIES);
        this.principal = null;
        this.rawKey = rawKey;
        setAuthenticated(false);
    }

    private ApiKeyAuthentication(Object principal, String role) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.principal = principal;
        this.rawKey = null;
        setAuthenticated(true);
    }

    public static ApiKeyAuthentication unauthenticated(String rawKey) {
        return new ApiKeyAuthentication(rawKey);
    }

    public static ApiKeyAuthentication client(ClientIdentity identity) {
        return new ApiKeyAuthentication(identity, ROLE_CLIENT);
    }

    public static ApiKeyAuthentication admin() {
        return new ApiKeyAuthentication("admin", ROLE_ADMIN);
    }

    @Override
    public Object getCredentials() {
        return rawKey;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        if (principal instanceof ClientIdentity identity) {
            return identity.tenantName();
        }
        return principal == null ? "" : principal.toString();
    }
}
