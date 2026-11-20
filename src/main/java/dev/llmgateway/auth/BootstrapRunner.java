package dev.llmgateway.auth;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import dev.llmgateway.config.GatewayProperties;
import dev.llmgateway.config.GatewayProperties.Bootstrap;

/** Creates the dev tenant/key from config so a fresh checkout works with one curl command. */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final TenantService tenants;
    private final GatewayProperties properties;

    public BootstrapRunner(TenantService tenants, GatewayProperties properties) {
        this.tenants = tenants;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Bootstrap bootstrap = properties.bootstrap();
        if (bootstrap == null || !StringUtils.hasText(bootstrap.apiKey())) {
            log.info("No bootstrap API key configured; create tenants via POST /admin/tenants");
            return;
        }
        tenants.ensureBootstrapTenant(bootstrap.tenantName(), bootstrap.apiKey(),
                        bootstrap.requestsPerMinute(), bootstrap.dailyTokenBudget())
                .block(Duration.ofSeconds(30));
        log.info("Bootstrap tenant '{}' ready (key {}..., {} req/min, daily budget {})",
                bootstrap.tenantName(), ApiKeys.displayPrefix(bootstrap.apiKey()), bootstrap.requestsPerMinute(),
                bootstrap.dailyTokenBudget() == null ? "unlimited" : bootstrap.dailyTokenBudget() + " tokens");
    }
}
