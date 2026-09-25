package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

/** 下游调用异常骨架。 */
public class DownstreamException extends RuntimeException {

    private final DownstreamErrorType type;
    private final boolean retryable;
    private final boolean outcomeKnown;

    public DownstreamException(DownstreamErrorType type, String message, boolean retryable, boolean outcomeKnown) {
        super(message);
        this.type = type;
        this.retryable = retryable;
        this.outcomeKnown = outcomeKnown;
    }

    public DownstreamErrorType getType() { return type; }
    public boolean isRetryable() { return retryable; }
    public boolean isOutcomeKnown() { return outcomeKnown; }
}
