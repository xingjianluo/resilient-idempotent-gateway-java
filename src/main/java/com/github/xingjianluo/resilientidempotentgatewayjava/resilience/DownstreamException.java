package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

/**
 * 下游调用异常的内部基类，携带对外错误类型，不向外透出堆栈。
 */
public class DownstreamException extends RuntimeException {

    private final DownstreamErrorType errorType;
    private final boolean uncertain;

    public DownstreamException(DownstreamErrorType errorType, String message, boolean uncertain) {
        super(message);
        this.errorType = errorType;
        this.uncertain = uncertain;
    }

    public DownstreamException(DownstreamErrorType errorType, String message, boolean uncertain, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.uncertain = uncertain;
    }

    public DownstreamErrorType getErrorType() {
        return errorType;
    }

    /** 下游副作用是否可能已发生（超时/连接中断通常为 true） */
    public boolean isUncertain() {
        return uncertain;
    }
}
