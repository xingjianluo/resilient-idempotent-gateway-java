package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/** 获取幂等槽位的结果。 */
public record AcquireResult(AcquireOutcome outcome, IdempotentRecord record) {

    public static AcquireResult of(AcquireOutcome outcome, IdempotentRecord record) {
        return new AcquireResult(outcome, record);
    }
}
