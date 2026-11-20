package dev.llmgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import dev.llmgateway.auth.ApiKeyAuthentication;
import dev.llmgateway.auth.ApiKeyAuthenticationManager;
import dev.llmgateway.auth.ApiKeyConverter;
import dev.llmgateway.auth.SecurityErrorHandler;

/**
 * Stateless API-key security:
 * <ul>
 *   <li>/v1/** needs a tenant key (ROLE_CLIENT)</li>
 *   <li>/admin/** needs the admin key (ROLE_ADMIN)</li>
 *   <li>health and Prometheus endpoints are open (lock these down at the network layer in production)</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                  ApiKeyAuthenticationManager authenticationManager,
                                                  SecurityErrorHandler errorHandler,
                                                  CorsConfigurationSource corsConfigurationSource) {
        AuthenticationWebFilter apiKeyFilter = new AuthenticationWebFilter(authenticationManager);
        apiKeyFilter.setServerAuthenticationConverter(new ApiKeyConverter());
        apiKeyFilter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(errorHandler));
        apiKeyFilter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .addFilterAt(apiKeyFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/admin/**").hasRole(ApiKeyAuthentication.ROLE_ADMIN)
                        .pathMatchers("/v1/**").hasRole(ApiKeyAuthentication.ROLE_CLIENT)
                        .anyExchange().denyAll())
                .build();
    }

    /** Lets the (upcoming) Angular admin dashboard call the API from another origin. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.corsAllowedOrigins());
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.addExposedHeader("X-Request-Id");
        config.addExposedHeader("X-Gateway-Provider");
        config.addExposedHeader("X-Gateway-Fallbacks");
        config.addExposedHeader("Retry-After");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
