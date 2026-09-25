package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/**
 * 业务执行的终态结论：成功、确定性失败或不确定。
 */
public final class BusinessTerminal<T> {

    private final IdempotencyStatus status;
    private final T result;
    private final String errorCode;

    private BusinessTerminal(IdempotencyStatus status, T result, String errorCode) {
        this.status = status;
        this.result = result;
        this.errorCode = errorCode;
    }

    public static <T> BusinessTerminal<T> success(T result) {
        return new BusinessTerminal<>(IdempotencyStatus.SUCCEEDED, result, null);
    }

    public static <T> BusinessTerminal<T> failed(String errorCode) {
        return new BusinessTerminal<>(IdempotencyStatus.FAILED, null, errorCode);
    }

    public static <T> BusinessTerminal<T> uncertain(String errorCode) {
        return new BusinessTerminal<>(IdempotencyStatus.UNCERTAIN, null, errorCode);
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
}
