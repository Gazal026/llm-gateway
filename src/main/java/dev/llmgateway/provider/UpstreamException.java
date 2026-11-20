package dev.llmgateway.provider;

/**
 * An upstream provider answered with an error status (or an error event mid-stream).
 */
public class UpstreamException extends RuntimeException {

    private final String provider;
    private final int status;
    private final String body;

    public UpstreamException(String provider, int status, String body) {
        super("Provider '" + provider + "' returned HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
        this.provider = provider;
        this.status = status;
        this.body = body;
    }

    public String provider() {
        return provider;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    /**
     * Whether another provider might succeed. A 400/404/413/422 means the request itself is the problem,
     * so trying a different provider would just fail again (and cost money). Everything else, including
     * 401/403 (our credentials for that provider are wrong) and 429 (that provider is saturated), is worth a fallback.
     */
    public boolean retryable() {
        return switch (status) {
            case 400, 404, 413, 422 -> false;
            default -> true;
        };
    }

    /** Whether this error says something about the provider's health (feeds the circuit breaker). */
    public boolean indicatesProviderFailure() {
        return status >= 500 || status == 401 || status == 403;
    }
}
