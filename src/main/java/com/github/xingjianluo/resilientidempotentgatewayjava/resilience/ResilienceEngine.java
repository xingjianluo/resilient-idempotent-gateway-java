package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

import com.github.xingjianluo.resilientidempotentgatewayjava.config.GatewayProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 隔离 + 超时 + 重试 + 熔断的组合执行器。
 *
 * <p>四类策略按依赖名各自独立维护（互不干扰）：
 * <ul>
 *   <li>并发隔离：每依赖独立信号量，占满立即失败，不排队；</li>
 *   <li>超时：独立线程池执行 future + 超时取消；超时后副作用未知，不再重试；</li>
 *   <li>重试：仅对“结果确定且可重试”的失败重试，退避后再次执行；</li>
 *   <li>熔断：连续失败跳闸，冷却后仅放行一次试探，成功关闭/失败重开。</li>
 * </ul>
 */
public class ResilienceEngine {

    private static final Logger log = LoggerFactory.getLogger(ResilienceEngine.class);

    private final ExecutorService timeoutExecutor;
    private final GatewayProperties properties;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> bulkheads = new ConcurrentHashMap<>();

    public ResilienceEngine(ExecutorService timeoutExecutor, GatewayProperties properties) {
        this.timeoutExecutor = timeoutExecutor;
        this.properties = properties;
    }

    private GatewayProperties.Rule rule(String dependency) {
        return properties.getResilience().ruleFor(dependency);
    }

    private CircuitBreaker breaker(String dependency) {
        return breakers.computeIfAbsent(dependency, d -> {
            GatewayProperties.Rule r = rule(d);
            return new CircuitBreaker(d, r.getFailureThreshold(), r.getOpenMillis());
        });
    }

    private Semaphore bulkhead(String dependency) {
        return bulkheads.computeIfAbsent(dependency, d -> new Semaphore(
                Math.max(1, rule(d).getBulkheadMaxConcurrent()), true));
    }

    /** 测试辅助：直接查看某依赖熔断器状态。 */
    public CircuitState circuitState(String dependency) {
        return breaker(dependency).currentState();
    }

    /** 测试辅助：强制重置某依赖熔断器。 */
    public void resetCircuit(String dependency) {
        breakers.remove(dependency);
    }

    public <T> DownstreamOutcome<T> execute(String dependency, DownstreamCall<T> call) {
        GatewayProperties.Rule r = rule(dependency);
        CircuitBreaker cb = breaker(dependency);
        Semaphore semaphore = bulkhead(dependency);
        long start = System.currentTimeMillis();

        // 1) 熔断门
        if (!cb.tryAcquirePermission(start)) {
            log.warn("dependency[{}] rejected by circuit breaker state={}", dependency, cb.currentState());
            return DownstreamOutcome.fail(DownstreamErrorType.CIRCUIT_OPEN,
                    "downstream circuit is open", true, 0, cb.currentState());
        }

        // 2) 并发隔离门（独立于熔断，二者互不影响）
        boolean probing = cb.currentState() == CircuitState.HALF_OPEN;
        if (!semaphore.tryAcquire()) {
            log.warn("dependency[{}] rejected by bulkhead, available permits={}",
                    dependency, semaphore.availablePermits());
            // 被隔离的请求根本没有发出，不改变熔断计数
            return DownstreamOutcome.fail(DownstreamErrorType.BULKHEAD_FULL,
                    "downstream concurrency limit reached", true, 0, cb.currentState());
        }

        int attempts = 0;
        try {
            int maxAttempts = Math.max(1, r.getMaxAttempts());
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                attempts = attempt;
                long attemptStart = System.currentTimeMillis();
                Future<T> future = null;
                try {
                    future = timeoutExecutor.submit(call);
                    T value = future.get(r.getCallTimeoutMillis(), TimeUnit.MILLISECONDS);
                    long cost = System.currentTimeMillis() - attemptStart;
                    log.info("dependency[{}] attempt {}/{} succeeded in {}ms (probe={})",
                            dependency, attempt, maxAttempts, cost, probing);
                    cb.recordSuccess();
                    return DownstreamOutcome.ok(value, attempt, CircuitState.CLOSED);
                } catch (TimeoutException te) {
                    // 超时：下游是否已生效未知，取消无意义也不重试，直接判定 UNCERTAIN
                    cancelQuietly(future);
                    long cost = System.currentTimeMillis() - attemptStart;
                    log.warn("dependency[{}] attempt {}/{} timed out after {}ms; outcome UNCERTAIN, no retry",
                            dependency, attempt, maxAttempts, cost);
                    cb.recordFailure(System.currentTimeMillis());
                    return DownstreamOutcome.fail(DownstreamErrorType.TIMEOUT,
                            "downstream call timed out; result is uncertain",
                            false, attempt, cb.currentState());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    cancelQuietly(future);
                    return DownstreamOutcome.fail(DownstreamErrorType.UNKNOWN,
                            "interrupted while calling downstream", false, attempt, cb.currentState());
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (cause instanceof DownstreamException de) {
                        log.warn("dependency[{}] attempt {}/{} failed type={} retryable={} outcomeKnown={} msg={}",
                                dependency, attempt, maxAttempts, de.getType(), de.isRetryable(),
                                de.isOutcomeKnown(), de.getMessage());
                        // 业务拒绝等“结果确定”的失败要计入熔断；仅可重试且结果确定时才重试
                        cb.recordFailure(System.currentTimeMillis());
                        if (de.isRetryable() && de.isOutcomeKnown() && attempt < maxAttempts) {
                            sleepBackoff(r.getBackoffMillis());
                            continue;
                        }
                        DownstreamErrorType mapped = de.isRetryable()
                                ? DownstreamErrorType.RETRY_EXHAUSTED
                                : de.getType();
                        return DownstreamOutcome.fail(mapped, de.getMessage(),
                                de.isOutcomeKnown(), attempt, cb.currentState());
                    }
                    // 未受控异常不外泄堆栈细节
                    log.error("dependency[{}] attempt {}/{} raised unexpected exception",
                            dependency, attempt, maxAttempts, cause);
                    cb.recordFailure(System.currentTimeMillis());
                    return DownstreamOutcome.fail(DownstreamErrorType.DOWNSTREAM_ERROR,
                            "downstream call failed", false, attempt, cb.currentState());
                }
            }
            return DownstreamOutcome.fail(DownstreamErrorType.RETRY_EXHAUSTED,
                    "downstream retries exhausted", true, attempts, cb.currentState());
        } finally {
            semaphore.release();
        }
    }

    private void sleepBackoff(long backoffMillis) {
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void cancelQuietly(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }
}
