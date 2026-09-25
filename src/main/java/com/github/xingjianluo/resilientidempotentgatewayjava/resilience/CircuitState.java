package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

/** 熔断器状态。OPEN 恢复时先进入 HALF_OPEN 试探。 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
