package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/**
 * 幂等入口的执行结论。要么执行业务（EXECUTED），要么重放既有终态（REPLAYED）。
 */
public final class IdempotencyOutcome<T> {

    public enum Kind {
        /** 本次实际执行业务（副作用至多发生一次） */
        EXECUTED,
        /** 重复请求，重放既有终态结果，未再次执行 */
        REPLAYED
    }

    private final Kind kind;
    private final IdempotencyStatus status;
    private final T result;
    private final String errorCode;
    private final String traceId;

    private IdempotencyOutcome(Kind kind, IdempotencyStatus status, T result, String errorCode, String traceId) {
        this.kind = kind;
        this.status = status;
        this.result = result;
        this.errorCode = errorCode;
        this.traceId = traceId;
    }

    public static <T> IdempotencyOutcome<T> executed(IdempotencyStatus status, T result,
                                                     String errorCode, String traceId) {
        return new IdempotencyOutcome<>(Kind.EXECUTED, status, result, errorCode, traceId);
    }

    public static <T> IdempotencyOutcome<T> replayed(IdempotencyStatus status, T result,
                                                     String errorCode, String traceId) {
        return new IdempotencyOutcome<>(Kind.REPLAYED, status, result, errorCode, traceId);
    }

    public Kind getKind() {
        return kind;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public T getResult() {
        return result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getTraceId() {
        return traceId;
    }
}
