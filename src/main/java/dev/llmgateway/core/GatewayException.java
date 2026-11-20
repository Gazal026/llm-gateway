package dev.llmgateway.core;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.server.ServerWebInputException;

import dev.llmgateway.provider.UpstreamException;

/**
 * An error the gateway reports to its client, carrying the HTTP status and OpenAI-style error type/code.
 * {@link #from(Throwable)} is the single place where internal failures become client-facing errors.
 */
public class GatewayException extends RuntimeException {

    private static final Logger log = LoggerFactory.getLogger(GatewayException.class);

    private final HttpStatus status;
    private final String type;
    private final String code;
    private final Duration retryAfter;

    public GatewayException(HttpStatus status, String type, String code, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.type = type;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public static GatewayException badRequest(String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message, null, null);
    }

    public static GatewayException notFound(String message) {
        return new GatewayException(HttpStatus.NOT_FOUND, "invalid_request_error", "not_found", message, null, null);
    }

    public static GatewayException modelNotFound(String model) {
        return new GatewayException(HttpStatus.NOT_FOUND, "invalid_request_error", "model_not_found",
                "Unknown model '" + model + "'. Use GET /v1/models to list aliases, or '<provider>/<model>'.", null, null);
    }

    public static GatewayException noProviders(String model) {
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "no_provider_configured",
                "No configured provider can serve '" + model + "'. Check API keys for the providers in this route.", null, null);
    }

    public static GatewayException rateLimited(Duration retryAfter) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", "rate_limit_exceeded",
                "Rate limit exceeded for this tenant. Retry after " + seconds(retryAfter) + "s.", retryAfter, null);
    }

    public static GatewayException budgetExceeded(Duration retryAfter) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "insufficient_quota", "daily_token_budget_exceeded",
                "Daily token budget exhausted for this tenant. It resets at 00:00 UTC.", retryAfter, null);
    }

    /** Converts any failure into the error the client should see. */
    public static GatewayException from(Throwable error) {
        if (error instanceof GatewayException gatewayException) {
            return gatewayException;
        }
        if (error instanceof UpstreamException upstream && !upstream.retryable()) {
            HttpStatus status = HttpStatus.resolve(upstream.status());
            return new GatewayException(status == null ? HttpStatus.BAD_REQUEST : status, "invalid_request_error",
                    "upstream_rejected_request", "Upstream provider rejected the request: " + upstream.body(), null, error);
        }
        if (error instanceof CallNotPermittedException) {
            return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "providers_unavailable",
                    "All providers for this model are temporarily unavailable (circuit open). Try again shortly.",
                    Duration.ofSeconds(10), error);
        }
        if (error instanceof TimeoutException) {
            return new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "server_error", "upstream_timeout",
                    "Upstream providers did not respond in time.", null, error);
        }
        if (error instanceof UpstreamException || error instanceof WebClientException || error instanceof IOException) {
            return new GatewayException(HttpStatus.BAD_GATEWAY, "server_error", "upstream_error",
                    "All upstream providers failed. Last error: " + error.getMessage(), null, error);
        }
        if (error instanceof ServerWebInputException || error instanceof DecodingException) {
            Throwable root = rootCause(error);
            return badRequest("Malformed request body: " + root.getMessage());
        }
        log.error("Unexpected gateway error", error);
        return new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "internal_error",
                "Internal gateway error.", null, error);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static long seconds(Duration duration) {
        return Math.max(1, (long) Math.ceil(duration.toMillis() / 1000.0));
    }

    public long retryAfterSeconds() {
        return retryAfter == null ? 0 : seconds(retryAfter);
    }
}
