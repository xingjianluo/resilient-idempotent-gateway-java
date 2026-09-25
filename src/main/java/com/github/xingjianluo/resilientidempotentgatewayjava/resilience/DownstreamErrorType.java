package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

/** 下游失败的可区分错误类型。 */
public enum DownstreamErrorType {
    TIMEOUT,
    BUSINESS_REJECTED,
    RETRY_EXHAUSTED,
    CIRCUIT_OPEN,
    BULKHEAD_FULL,
    DOWNSTREAM_ERROR,
    UNKNOWN
}
