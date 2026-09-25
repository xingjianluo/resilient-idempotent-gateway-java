package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

/**
 * 下游调用失败的对外可区分错误类型（不暴露内部异常与堆栈）。
 */
public enum DownstreamErrorType {
    /** 下游返回确定性业务拒绝/错误 */
    REMOTE_ERROR("DOWNSTREAM_REMOTE_ERROR", 502),
    /** 下游调用超时，副作用状态未知 */
    TIMEOUT("DOWNSTREAM_TIMEOUT", 504),
    /** 重试次数耗尽仍失败 */
    RETRY_EXHAUSTED("DOWNSTREAM_RETRY_EXHAUSTED", 502),
    /** 熔断器打开，快速拒绝 */
    CIRCUIT_OPEN("DOWNSTREAM_CIRCUIT_OPEN", 503),
    /** 并发隔离槽位已满 */
    BULKHEAD_FULL("DOWNSTREAM_BULKHEAD_FULL", 503);

    private final String code;
    private final int httpStatus;

    DownstreamErrorType(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
