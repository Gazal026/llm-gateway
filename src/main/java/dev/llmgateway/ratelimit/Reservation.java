package dev.llmgateway.ratelimit;

/**
 * Tokens provisionally taken from a tenant's daily budget before the call.
 * Because the real token count is only known when the response finishes (often mid-stream),
 * the gateway reserves an estimate up front and reconciles the difference afterwards.
 *
 * @param budgetKey      Redis key of the day's budget counter, or null when the tenant is unmetered
 * @param reservedTokens tokens reserved up front (prompt estimate + max output tokens)
 * @param promptEstimate estimated prompt tokens, used if the provider doesn't report usage
 */
public record Reservation(String budgetKey, long reservedTokens, int promptEstimate) {

    public static Reservation unmetered(int promptEstimate, long reservedTokens) {
        return new Reservation(null, reservedTokens, promptEstimate);
    }

    public boolean metered() {
        return budgetKey != null;
    }
}
