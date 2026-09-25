package com.github.xingjianluo.resilientidempotentgatewayjava.web;

import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandResponse;
import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
import com.github.xingjianluo.resilientidempotentgatewayjava.trace.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 兜底异常处理：任何未受控异常都映射为稳定错误码，内部异常与堆栈绝不直接透出。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommandResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        String traceId = TraceIds.newTraceId();
        log.warn("[{}] malformed request body: {}", traceId, SensitiveDataSanitizer.mask(ex.toString()));
        return ResponseEntity.badRequest().body(new CommandResponse(
                traceId, null, false, "INVALID_REQUEST", "request body is unreadable", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommandResponse> handleAny(Exception ex) {
        String traceId = TraceIds.newTraceId();
        // 仅记录脱敏后的异常类型，不向调用方回传堆栈
        log.error("[{}] unhandled exception: {}", traceId, SensitiveDataSanitizer.mask(ex.toString()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new CommandResponse(
                traceId, null, false, "INTERNAL_ERROR",
                "an internal error occurred; the request may not have been executed", null));
    }
}
