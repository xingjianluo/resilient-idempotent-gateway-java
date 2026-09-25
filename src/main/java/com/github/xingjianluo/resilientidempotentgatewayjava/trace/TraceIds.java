package com.github.xingjianluo.resilientidempotentgatewayjava.trace;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 追踪标识生成与校验工具。
 *
 * <p>{@code traceId} 贯穿入口、业务与下游调用：入口优先复用调用方传入的
 * {@code X-Trace-Id}（校验失败则重新生成），保证同一请求在各环节可关联。
 */
public final class TraceIds {

    /** 只允许字母、数字与少量安全分隔符，杜绝日志换行/转义注入。 */
    private static final String SAFE_PATTERN = "[A-Za-z0-9._-]{8,64}";

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIds() {
    }

    /** 生成形如 {@code tr-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx} 的追踪标识。 */
    public static String newTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "tr-" + HexFormat.of().formatHex(bytes);
    }

    /**
     * 若调用方传入的标识合法则复用，否则生成新标识。
     * 入参会被严格限制字符集，避免伪造标识污染日志。
     */
    public static String resolve(String incoming) {
        if (incoming != null && incoming.matches(SAFE_PATTERN)) {
            return incoming;
        }
        return newTraceId();
    }
}
