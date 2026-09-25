package com.github.xingjianluo.resilientidempotentgatewayjava.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个 HTTP 请求建立/透传 traceId：
 * <ul>
 *     <li>优先使用入站 {@code X-Trace-Id}，否则生成新标识；</li>
 *     <li>写入 MDC 供整条链路的日志关联；</li>
 *     <li>响应头回写 {@code X-Trace-Id}，便于调用方对账。</li>
 * </ul>
 */
@Component
@Order(0)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.begin(request.getHeader(TraceContext.TRACE_HEADER));
        response.setHeader(TraceContext.TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.end();
        }
    }
}
