package com.github.xingjianluo.resilientidempotentgatewayjava.command;

/**
 * 对外统一响应体：只包含可解释的稳定字段，不含内部异常与堆栈。
 *
 * @param traceId       贯穿入口/业务/下游的追踪标识
 * @param idempotencyKey 幂等键（回显）
 * @param replay        是否为重复请求收敛返回
 * @param code          机器可判定的结果码
 * @param message       可读说明（已脱敏）
 * @param data          成功数据；失败为 null
 */
public record CommandResponse(
        String traceId,
        String idempotencyKey,
        boolean replay,
        String code,
        String message,
        Object data) {

    public static CommandResponse from(CommandResult result, String idempotencyKey) {
        return new CommandResponse(
                result.traceId(),
                idempotencyKey,
                result.replay(),
                result.code(),
                result.message(),
                result.data());
    }
}
