package com.github.xingjianluo.resilientidempotentgatewayjava.command;

/**
 * 业务执行结果（内部传递）：携带 HTTP 状态码、错误码、可读消息、数据与追踪标识。
 * 不携带任何内部异常或堆栈。
 */
public record CommandResult(
        int httpStatus,
        String code,
        String message,
        Object data,
        boolean replay,
        String traceId) {

    public boolean success() {
        return httpStatus >= 200 && httpStatus < 300;
    }

    public static CommandResult ok(int httpStatus, String code, String message, Object data,
                                   boolean replay, String traceId) {
        return new CommandResult(httpStatus, code, message, data, replay, traceId);
    }

    public static CommandResult error(int httpStatus, String code, String message,
                                      Object data, String traceId) {
        return new CommandResult(httpStatus, code, message, data, false, traceId);
    }

    public static CommandResult error(int httpStatus, String code, String message,
                                      boolean replay, String traceId) {
        return new CommandResult(httpStatus, code, message, null, replay, traceId);
    }

    public static CommandResult error(int httpStatus, String code, String message, Object data,
                                      boolean replay, String traceId) {
        return new CommandResult(httpStatus, code, message, data, replay, traceId);
    }
}
