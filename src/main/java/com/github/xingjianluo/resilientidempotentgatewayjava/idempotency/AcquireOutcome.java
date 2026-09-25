package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/** 幂等获取结论：各类拒绝/降级必须可区分。 */
public enum AcquireOutcome {
    ACCEPTED,
    REPLAY,
    PAYLOAD_MISMATCH,
    CONCURRENT,
    EXPIRED_REPLAY,
    UNCERTAIN_REPLAY,
    STORE_UNAVAILABLE
}
