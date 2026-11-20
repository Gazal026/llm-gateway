package dev.llmgateway.core;

import dev.llmgateway.api.openai.ChatCompletionChunk;
import dev.llmgateway.api.openai.ChatCompletionResponse;
import dev.llmgateway.api.openai.Usage;
import dev.llmgateway.routing.ResolvedTarget;

/**
 * Mutable bookkeeping for one gateway call: which target is active, fallbacks taken, first-token time and output seen.
 * Reactor delivers signals for a single subscription serially, so plain fields (volatile for visibility) suffice.
 */
final class CallState {

    private volatile ResolvedTarget target;
    private volatile long attemptStartNanos;
    private volatile long firstTokenNanos;
    private volatile int fallbacks;
    private volatile int completionChars;
    private volatile Usage usage;
    private volatile Throwable error;

    void startAttempt(ResolvedTarget next) {
        target = next;
        attemptStartNanos = System.nanoTime();
        firstTokenNanos = 0;
        completionChars = 0;
        usage = null;
    }

    void fellBack(Throwable cause) {
        fallbacks++;
        error = cause;
    }

    void failed(Throwable cause) {
        error = cause;
    }

    void observe(ChatCompletionChunk chunk) {
        if (chunk.usage() != null) {
            usage = chunk.usage();
        }
        int chars = chunk.contentChars();
        if (chars > 0) {
            if (firstTokenNanos == 0) {
                firstTokenNanos = System.nanoTime();
            }
            completionChars += chars;
        }
    }

    void observe(ChatCompletionResponse response) {
        usage = response.usage();
        completionChars = response.contentChars();
    }

    ResolvedTarget target() {
        return target;
    }

    long attemptStartNanos() {
        return attemptStartNanos;
    }

    long firstTokenNanos() {
        return firstTokenNanos;
    }

    int fallbacks() {
        return fallbacks;
    }

    int completionChars() {
        return completionChars;
    }

    Usage usage() {
        return usage;
    }

    Throwable error() {
        return error;
    }

    String providerName() {
        return target == null ? "none" : target.providerName();
    }
}
