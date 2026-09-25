package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

/**
 * 熔断器状态：关闭 -> 打开 -> 半开（试探）-> 关闭/打开。
 */
public enum CircuitBreakerState {
    CLOSED, OPEN, HALF_OPEN
}
