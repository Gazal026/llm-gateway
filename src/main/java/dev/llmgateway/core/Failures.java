package dev.llmgateway.core;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import dev.llmgateway.provider.UpstreamException;

/** The two policy decisions about upstream failures, kept in one place. */
public final class Failures {

    private Failures() {
    }

    /** Should the gateway try the next provider in the route after this error? */
    public static boolean isRetryable(Throwable error) {
        if (error instanceof GatewayException) {
            return false; // our own validation / policy errors: another provider won't help
        }
        if (error instanceof UpstreamException upstream) {
            return upstream.retryable();
        }
        return true; // timeouts, connection failures, open circuits, malformed upstream responses
    }

    /** Should this error count against the provider's circuit breaker? */
    public static boolean countsAgainstProvider(Throwable error) {
        if (error instanceof GatewayException || error instanceof CallNotPermittedException) {
            return false;
        }
        if (error instanceof UpstreamException upstream) {
            return upstream.indicatesProviderFailure();
        }
        return true;
    }

    /** Short, low-cardinality label for metrics. */
    public static String reason(Throwable error) {
        if (error instanceof UpstreamException upstream) {
            return "http_" + upstream.status();
        }
        if (error instanceof CallNotPermittedException) {
            return "circuit_open";
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "timeout";
        }
        return error.getClass().getSimpleName();
    }
}
