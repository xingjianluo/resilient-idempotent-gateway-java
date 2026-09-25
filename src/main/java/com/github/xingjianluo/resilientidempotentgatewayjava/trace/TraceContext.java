package com.github.xingjianluo.resilientidempotentgatewayjava.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 基于 MDC 的追踪上下文。
 * <p>
 * 同一次命令请求从 HTTP 入口、幂等判定、业务执行到下游调用共享同一 traceId；
 * 支持透传入站 {@code X-Trace-Id}，缺省时生成短标识。
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String TRACE_HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    /** 当前 MDC 中的 traceId，可能为 null（非请求线程）。 */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    /**
     * 开始一段追踪上下文。
     *
     * @param inboundTraceId 入站标识（空白则生成新标识）
     * @return 生效的 traceId
     */
    public static String begin(String inboundTraceId) {
        String traceId = (inboundTraceId == null || inboundTraceId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : inboundTraceId.trim();
        MDC.put(TRACE_ID, traceId);
        return traceId;
    }

    public static void end() {
        MDC.remove(TRACE_ID);
    }
}
