package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

/** 经过韧性策略处理后的最终下游结论。 */
public record DownstreamOutcome<T>(
        boolean success,
        T value,
        DownstreamErrorType errorType,
        String errorMessage,
        boolean outcomeKnown,
        int attempts,
        CircuitState circuitState) {

    public static <T> DownstreamOutcome<T> ok(T value, int attempts, CircuitState circuitState) {
        return new DownstreamOutcome<>(true, value, null, null, true, attempts, circuitState);
    }

    public static <T> DownstreamOutcome<T> fail(DownstreamErrorType type, String message,
                                                boolean outcomeKnown, int attempts, CircuitState circuitState) {
        return new DownstreamOutcome<>(false, null, type, message, outcomeKnown, attempts, circuitState);
    }
}
