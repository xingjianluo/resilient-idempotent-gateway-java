package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/** 幂等请求生命周期状态。 */
public enum RequestStatus {
    PROCESSING,
    SUCCESS,
    FAILED,
    UNCERTAIN
}
