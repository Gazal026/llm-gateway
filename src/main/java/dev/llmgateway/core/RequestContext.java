package dev.llmgateway.core;

import dev.llmgateway.auth.ClientIdentity;

/** Per-request facts shared across the pipeline. {@code startNanos} comes from {@link System#nanoTime()}. */
public record RequestContext(String requestId, ClientIdentity client, long startNanos) {
}
