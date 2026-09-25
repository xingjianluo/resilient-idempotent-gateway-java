package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/**
 * 幂等入口给出的确定性拒绝/降级结论（不携带内部堆栈信息）。
 */
public class IdempotencyRejectedException extends RuntimeException {

    private final IdempotencyRejectCode rejectCode;
    private final String traceId;

    public IdempotencyRejectedException(IdempotencyRejectCode rejectCode, String message, String traceId) {
        super(message);
        this.rejectCode = rejectCode;
        this.traceId = traceId;
    }

    public IdempotencyRejectCode getRejectCode() {
        return rejectCode;
    }

    public String getTraceId() {
        return traceId;
    }
}
