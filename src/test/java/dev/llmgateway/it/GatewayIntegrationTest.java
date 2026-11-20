package dev.llmgateway.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests against real Postgres and Redis (Testcontainers) with two fake
 * OpenAI-compatible upstreams (WireMock), so failover is tested over real HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    private static final String CLIENT_KEY = "sk-test-client";
    private static final String ADMIN_KEY = "test-admin-key";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static final WireMockServer PRIMARY = new WireMockServer(options().dynamicPort());
    static final WireMockServer SECONDARY = new WireMockServer(options().dynamicPort());

    static {
        PRIMARY.start();
        SECONDARY.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String database = "//" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql:" + database);
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("gateway.admin-key", () -> ADMIN_KEY);
        registry.add("gateway.bootstrap.api-key", () -> CLIENT_KEY);
        registry.add("gateway.bootstrap.requests-per-minute", () -> 1000);
        registry.add("gateway.providers.mock.tokens-per-second", () -> 10000);

        registry.add("gateway.providers.primary.type", () -> "openai");
        registry.add("gateway.providers.primary.base-url", () -> PRIMARY.baseUrl() + "/v1");
        registry.add("gateway.providers.secondary.type", () -> "openai");
        registry.add("gateway.providers.secondary.base-url", () -> SECONDARY.baseUrl() + "/v1");
        registry.add("gateway.routes.resilient[0].provider", () -> "primary");
        registry.add("gateway.routes.resilient[0].model", () -> "model-a");
        registry.add("gateway.routes.resilient[1].provider", () -> "secondary");
        registry.add("gateway.routes.resilient[1].model", () -> "model-b");
    }

    @Autowired
    WebTestClient client;

    @Autowired
    DatabaseClient db;

    @BeforeEach
    void resetUpstreams() {
        PRIMARY.resetAll();
        SECONDARY.resetAll();
        client = client.mutate().responseTimeout(Duration.ofSeconds(15)).build();
    }

    @AfterAll
    static void stopUpstreams() {
        PRIMARY.stop();
        SECONDARY.stop();
    }

    @Test
    void requestsWithoutAKeyGetAnOpenAiStyle401() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chat("mock", false))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key");
    }

    @Test
    void unknownModelIs404() {
        authorized(chat("no-such-model", false))
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("model_not_found");
    }

    @Test
    void fallsBackToSecondaryProviderWhenPrimaryIsDown() {
        PRIMARY.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":{\"message\":\"overloaded\"}}")));
        SECONDARY.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"model-b",
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from secondary"},
                                             "finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                                """)));
        String requestId = "it-" + UUID.randomUUID();

        client.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + CLIENT_KEY)
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chat("resilient", false))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Gateway-Provider", "secondary")
                .expectHeader().valueEquals("X-Gateway-Fallbacks", "1")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Hello from secondary")
                .jsonPath("$.usage.total_tokens").isEqualTo(15);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> row = db.sql("SELECT * FROM usage_events WHERE request_id = :id")
                    .bind("id", requestId)
                    .fetch().one().block();
            assertThat(row).isNotNull();
            assertThat(row.get("provider")).isEqualTo("secondary");
            assertThat(row.get("status")).isEqualTo("success");
            assertThat(row.get("fallback_count")).isEqualTo(1);
            assertThat(row.get("prompt_tokens")).isEqualTo(10);
            assertThat(row.get("tokens_estimated")).isEqualTo(false);
        });
    }

    @Test
    void streamingFallsBackBeforeTheFirstTokenAndHidesUsageUnlessAsked() {
        PRIMARY.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500)));
        SECONDARY.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("\"stream\":true"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"model-b","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}

                                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"model-b","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

                                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"model-b","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}

                                data: [DONE]

                                """)));

        List<String> events = client.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + CLIENT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chat("resilient", true))
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .map(ServerSentEvent::data)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).contains("\"content\":\"Hel\"");
        assertThat(events.get(1)).contains("\"content\":\"lo\"");
        assertThat(events.get(2)).isEqualTo("[DONE]");
    }

    @Test
    void tenantsAreRateLimitedWithRetryAfter() {
        String key = createTenant(2, null);

        for (int i = 0; i < 2; i++) {
            authorized(key, chat("mock", false)).expectStatus().isOk();
        }
        authorized(key, chat("mock", false))
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("rate_limit_exceeded");
    }

    @Test
    void requestsThatWouldExceedTheDailyTokenBudgetAreRejected() {
        String key = createTenant(100, 100L);
        Map<String, Object> body = Map.of(
                "model", "mock",
                "max_tokens", 500,
                "messages", List.of(Map.of("role", "user", "content", "hi")));

        authorized(key, body)
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("daily_token_budget_exceeded");
    }

    @Test
    void adminEndpointsRejectClientKeys() {
        client.get().uri("/admin/tenants")
                .header("Authorization", "Bearer " + CLIENT_KEY)
                .exchange()
                .expectStatus().isForbidden();
    }

    private String createTenant(int requestsPerMinute, Long dailyTokenBudget) {
        Map<String, Object> body = dailyTokenBudget == null
                ? Map.of("name", "tenant-" + UUID.randomUUID(), "requests_per_minute", requestsPerMinute)
                : Map.of("name", "tenant-" + UUID.randomUUID(), "requests_per_minute", requestsPerMinute,
                        "daily_token_budget", dailyTokenBudget);
        JsonNode created = client.post().uri("/admin/tenants")
                .header("Authorization", "Bearer " + ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        return created.get("api_key").asText();
    }

    private WebTestClient.ResponseSpec authorized(Object body) {
        return authorized(CLIENT_KEY, body);
    }

    private WebTestClient.ResponseSpec authorized(String key, Object body) {
        return client.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static Map<String, Object> chat(String model, boolean stream) {
        return Map.of(
                "model", model,
                "stream", stream,
                "messages", List.of(Map.of("role", "user", "content", "Say hello")));
    }
}
