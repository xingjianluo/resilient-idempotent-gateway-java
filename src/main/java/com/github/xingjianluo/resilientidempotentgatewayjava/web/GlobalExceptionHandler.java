package com.github.xingjianluo.resilientidempotentgatewayjava.web;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRejectCode;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRejectedException;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.BusinessException;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamErrorType;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamException;
import com.github.xingjianluo.resilientidempotentgatewayjava.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将各类内部异常映射为可区分的对外错误。
 * <p>
 * 对外响应只包含 traceId、稳定错误码与可解释文案；内部异常类型与堆栈仅记录在服务端日志，
 * 绝不透出。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IdempotencyRejectedException.class)
    public ResponseEntity<ApiError> handleIdempotencyRejected(IdempotencyRejectedException ex) {
        IdempotencyRejectCode code = ex.getRejectCode();
        log.info("idempotency rejection code={} traceId={}", code.code(), ex.getTraceId());
        return ResponseEntity.status(code.httpStatus())
                .body(new ApiError(ex.getTraceId(), code.code(), ex.getMessage()));
    }

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<ApiError> handleDownstream(DownstreamException ex) {
        DownstreamErrorType type = ex.getErrorType();
        String traceId = TraceContext.currentTraceId();
        // 仅记录异常类型与错误码，不向外返回堆栈
        log.warn("downstream failure mapped code={} uncertain={} traceId={}",
                type.code(), ex.isUncertain(), traceId);
        return ResponseEntity.status(type.httpStatus())
                .body(new ApiError(traceId, type.code(), ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        String traceId = TraceContext.currentTraceId();
        log.info("business failure code={} traceId={}", ex.getBusinessErrorCode(), traceId);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError(traceId, ex.getBusinessErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        String traceId = TraceContext.currentTraceId();
        log.info("bad request traceId={} reason={}", traceId, ex.toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(traceId, "BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        String traceId = TraceContext.currentTraceId();
        // 完整堆栈仅留在服务端日志；对外返回无敏感细节的通用错误
        log.error("unexpected error traceId={}", traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(traceId, "INTERNAL_ERROR", "an internal error occurred"));
    }
}
