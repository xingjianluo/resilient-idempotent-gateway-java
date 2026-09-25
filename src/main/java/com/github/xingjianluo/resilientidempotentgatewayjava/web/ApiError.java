package com.github.xingjianluo.resilientidempotentgatewayjava.web;

/**
 * 统一对外错误体：只包含稳定错误码与可解释信息，不包含内部堆栈。
 */
public record ApiError(String traceId, String code, String message) {
}
