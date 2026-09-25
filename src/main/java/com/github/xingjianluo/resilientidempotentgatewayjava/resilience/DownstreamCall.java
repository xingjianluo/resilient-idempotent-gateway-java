package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

import java.util.concurrent.Callable;

/**
 * 下游调用：可返回结果；以 {@link DownstreamException} 表达可区分失败。
 */
@FunctionalInterface
public interface DownstreamCall<T> extends Callable<T> {
}
