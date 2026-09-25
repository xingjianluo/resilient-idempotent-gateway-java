package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个依赖的熔断器，状态迁移：
 *
 * <pre>
 *   CLOSED --连续失败达阈值--> OPEN
 *   OPEN   --冷却结束--> HALF_OPEN（仅放行一个试探请求）
 *   HALF_OPEN --试探成功--> CLOSED（失败计数清零）
 *   HALF_OPEN --试探失败--> OPEN（冷却时间重新计时）
 * </pre>
 *
 * 试探与普通请求严格区分：HALF_OPEN 期间除试探外的请求一律拒绝。
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String dependency;
    private final int failureThreshold;
    private final long openMillis;
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAtMillis = new AtomicLong(0L);

    public CircuitBreaker(String dependency, int failureThreshold, long openMillis) {
        this.dependency = dependency;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(1L, openMillis);
    }

    public String getDependency() {
        return dependency;
    }

    public CircuitState currentState() {
        return state.get();
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * 是否允许本次调用执行。
     *
     * @return true 表示可以真正发起下游调用（可能是普通请求，也可能是 HALF_OPEN 的唯一试探请求）
     */
    public boolean tryAcquirePermission(long nowMillis) {
        while (true) {
            CircuitState s = state.get();
            if (s == CircuitState.CLOSED) {
                return true;
            }
            if (s == CircuitState.HALF_OPEN) {
                // 试探请求已在途，其余请求一律拒绝
                return false;
            }
            // OPEN：冷却结束后，仅一个线程能 CAS 进入 HALF_OPEN 成为试探请求
            if (nowMillis - openedAtMillis.get() >= openMillis) {
                if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    log.info("circuit[{}] OPEN -> HALF_OPEN, allowing one probe request", dependency);
                    return true;
                }
                continue;
            }
            return false;
        }
    }

    /** 记录一次成功：试探成功才会关闭熔断；CLOSED 下成功则清零计数。 */
    public void recordSuccess() {
        CircuitState previous = state.getAndSet(CircuitState.CLOSED);
        consecutiveFailures.set(0);
        if (previous != CircuitState.CLOSED) {
            log.info("circuit[{}] {} -> CLOSED after successful probe", dependency, previous);
        }
    }

    /** 记录一次失败：CLOSED 下累计计数，达标则跳闸；试探失败则重新 OPEN。 */
    public void recordFailure(long nowMillis) {
        CircuitState s = state.get();
        if (s == CircuitState.HALF_OPEN) {
            openedAtMillis.set(nowMillis);
            state.set(CircuitState.OPEN);
            log.warn("circuit[{}] HALF_OPEN -> OPEN, probe failed; cooldown restarts", dependency);
            return;
        }
        if (s == CircuitState.OPEN) {
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && state.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
            openedAtMillis.set(nowMillis);
            log.warn("circuit[{}] CLOSED -> OPEN after {} consecutive failures", dependency, failures);
        }
    }
}
