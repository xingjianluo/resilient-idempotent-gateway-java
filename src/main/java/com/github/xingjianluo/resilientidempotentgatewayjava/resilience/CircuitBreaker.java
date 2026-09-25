package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

/**
 * 单依赖熔断器，线程安全。
 * <p>
 * 状态迁移：
 * <ul>
 *     <li>CLOSED：正常放行；统计窗口内累计达到最小调用数且失败率超阈值时打开；</li>
 *     <li>OPEN：快速失败；等待 {@code circuitOpenDuration} 后允许第一个试探请求，
 *         进入 HALF_OPEN；</li>
 *     <li>HALF_OPEN：同一时刻只允许一个试探请求；连续 {@code circuitTrialRequests}
 *         次试探全部成功 -> CLOSED（恢复）；任意一次试探失败 -> OPEN（重新冷却）。
 *         “试探成功”与“试探失败”因此被明确区分，不会互相掩盖。</li>
 * </ul>
 * 业务异常（{@link BusinessException}）不计入熔断失败统计，仅下游失败计入。
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final ResilienceProperties properties;
    private final Clock clock;
    private final String dependency;

    private CircuitBreakerState state = CircuitBreakerState.CLOSED;

    // CLOSED 统计窗口
    private int windowCalls;
    private int windowFailures;

    // OPEN -> HALF_OPEN 计时
    private Instant openedAt;

    // HALF_OPEN 顺序试探
    private int trialSuccesses;
    private boolean trialInFlight;

    public CircuitBreaker(ResilienceProperties properties, String dependency) {
        this(properties, dependency, Clock.systemUTC());
    }

    public CircuitBreaker(ResilienceProperties properties, String dependency, Clock clock) {
        this.properties = properties;
        this.dependency = dependency;
        this.clock = clock;
    }

    /**
     * 是否允许发起一次调用。
     */
    public synchronized boolean allowRequest() {
        if (state == CircuitBreakerState.CLOSED) {
            return true;
        }
        if (state == CircuitBreakerState.OPEN) {
            if (openedAt != null
                    && !clock.instant().isBefore(openedAt.plus(properties.getCircuitOpenDuration()))) {
                toHalfOpen();
                return beginTrial();
            }
            return false;
        }
        // HALF_OPEN：仅当没有在途试探时，允许下一次顺序试探
        if (!trialInFlight) {
            return beginTrial();
        }
        return false;
    }

    private boolean beginTrial() {
        trialInFlight = true;
        log.info("circuit HALF_OPEN trial admitted dependency={} priorSuccesses={}/{}",
                dependency, trialSuccesses, properties.getCircuitTrialRequests());
        return true;
    }

    /** 记录一次调用成功。 */
    public synchronized void recordSuccess() {
        if (state == CircuitBreakerState.HALF_OPEN) {
            trialInFlight = false;
            trialSuccesses++;
            log.info("circuit trial SUCCEEDED dependency={} successes={}/{}",
                    dependency, trialSuccesses, properties.getCircuitTrialRequests());
            if (trialSuccesses >= properties.getCircuitTrialRequests()) {
                toClosed();
            }
            return;
        }
        if (state == CircuitBreakerState.CLOSED) {
            windowCalls++;
        }
    }

    /** 记录一次下游失败（业务异常不应调用本方法）。 */
    public synchronized void recordFailure() {
        if (state == CircuitBreakerState.HALF_OPEN) {
            trialInFlight = false;
            log.warn("circuit trial FAILED dependency={} priorSuccesses={}, reopening",
                    dependency, trialSuccesses);
            toOpen();
            return;
        }
        if (state == CircuitBreakerState.CLOSED) {
            windowCalls++;
            windowFailures++;
            if (windowCalls >= properties.getCircuitMinimumCalls()) {
                double rate = (double) windowFailures / windowCalls;
                if (rate >= properties.getCircuitFailureRateThreshold()) {
                    log.warn("circuit opening dependency={} calls={} failures={} rate={}",
                            dependency, windowCalls, windowFailures, rate);
                    toOpen();
                }
            }
        }
    }

    private void toHalfOpen() {
        state = CircuitBreakerState.HALF_OPEN;
        trialSuccesses = 0;
        trialInFlight = false;
        log.info("circuit OPEN -> HALF_OPEN dependency={}", dependency);
    }

    private void toOpen() {
        state = CircuitBreakerState.OPEN;
        openedAt = clock.instant();
        trialInFlight = false;
        trialSuccesses = 0;
        log.warn("circuit -> OPEN dependency={} at={}", dependency, openedAt);
    }

    private void toClosed() {
        state = CircuitBreakerState.CLOSED;
        windowCalls = 0;
        windowFailures = 0;
        openedAt = null;
        trialInFlight = false;
        log.info("circuit HALF_OPEN -> CLOSED (recovered) dependency={}", dependency);
    }

    /** 当前状态（测试与日志使用）。 */
    public synchronized CircuitBreakerState currentState() {
        return state;
    }
}
