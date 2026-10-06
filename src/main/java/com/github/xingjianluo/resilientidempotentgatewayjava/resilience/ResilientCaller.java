package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对下游依赖的统一调用入口。
 * <p>
 * 四个策略各自独立、互不干扰，按以下顺序组合：
 * <ol>
 *     <li>熔断：{@link CircuitBreaker}，OPEN 时快速失败；HALF_OPEN 时仅放行顺序试探；</li>
 *     <li>并发隔离：{@link Bulkhead}（信号量），槽位满快速失败；整次调用（含重试）只占一个槽位；</li>
 *     <li>超时：每次尝试在依赖专属线程池上执行并独立计时，超时取消 future、
 *         中断执行线程，并标记“副作用未知”；</li>
 *     <li>重试：仅对可能瞬时恢复的下游失败（超时、远端错误）重试，达到上限映射为
 *         {@link DownstreamErrorType#RETRY_EXHAUSTED}。</li>
 * </ol>
 * 每个依赖拥有独立的熔断器、隔离槽与线程池，互不影响。
 * 内部异常与堆栈不会向外透出，只抛出携带 {@link DownstreamErrorType} 的
 * {@link DownstreamException}；业务异常 {@link BusinessException} 原样穿透。
 */
@Component
public class ResilientCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientCaller.class);

    private final ResilienceProperties properties;

    private record DependencyRuntime(CircuitBreaker breaker, Bulkhead bulkhead,
                                     ExecutorService executor) {
    }

    private final ConcurrentMap<String, DependencyRuntime> runtimes = new ConcurrentHashMap<>();

    public ResilientCaller(ResilienceProperties properties, TimeoutGuard timeoutGuard) {
        this.properties = properties;
    }

    private DependencyRuntime runtime(String dependency) {
        return runtimes.computeIfAbsent(dependency, this::createRuntime);
    }

    private DependencyRuntime createRuntime(String dependency) {
        CircuitBreaker breaker = new CircuitBreaker(properties, dependency);
        Bulkhead bulkhead = new Bulkhead(properties);
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "dep-" + dependency + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ExecutorService executor = Executors.newFixedThreadPool(properties.getExecutorPoolSize(), factory);
        log.info("initialized isolated runtime for dependency={} bulkhead={} poolSize={}",
                dependency, properties.getBulkheadMaxConcurrent(), properties.getExecutorPoolSize());
        return new DependencyRuntime(breaker, bulkhead, executor);
    }

    /**
     * 执行一次受保护的下游调用。
     */
    public <T> T call(String dependency, Callable<T> call) {
        DependencyRuntime rt = runtime(dependency);
        String traceId = MDC.get("traceId");

        // 1) 熔断门禁
        if (!rt.breaker().allowRequest()) {
            log.warn("[{}] circuit OPEN, fast reject dependency={}", traceId, dependency);
            throw new DownstreamException(DownstreamErrorType.CIRCUIT_OPEN,
                    "downstream dependency '" + dependency + "' circuit is open", false);
        }

        // 2) 并发隔离（整次调用含重试只占一个槽位）
        if (!rt.bulkhead().tryAcquire()) {
            log.warn("[{}] bulkhead full, fast reject dependency={}", traceId, dependency);
            throw new DownstreamException(DownstreamErrorType.BULKHEAD_FULL,
                    "downstream dependency '" + dependency + "' is at capacity", false);
        }

        DownstreamException last = null;
        boolean retried = false;
        try {
            int maxAttempts = Math.max(1, properties.getMaxAttempts());
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                AttemptResult<T> r = runAttempt(rt, dependency, call, attempt, traceId);
                if (r.success) {
                    rt.breaker().recordSuccess();
                    return r.value;
                }
                last = r.failure;

                // 熔断/隔离类失败不可重试；超时与远端错误按配置重试
                if (!retryable(last) || attempt == maxAttempts) {
                    break;
                }
                retried = true;
                log.info("[{}] retrying dependency={} after failure={} nextAttempt={}/{}",
                        traceId, dependency, last.getErrorType(), attempt + 1, maxAttempts);
                sleepBackoff(attempt);
                // 重试前再次经过熔断门禁
                if (!rt.breaker().allowRequest()) {
                    log.warn("[{}] circuit opened during retries dependency={}", traceId, dependency);
                    throw new DownstreamException(DownstreamErrorType.CIRCUIT_OPEN,
                            "downstream dependency '" + dependency + "' circuit is open", false);
                }
            }

            // 记录最终失败到熔断器（一次调用只记一次）
            rt.breaker().recordFailure();

            if (retried) {
                // 经历过重试仍失败：对外明确为 RETRY_EXHAUSTED，保留“是否不确定”语义
                log.warn("[{}] retries exhausted dependency={} lastError={}",
                        traceId, dependency, last.getErrorType());
                throw new DownstreamException(DownstreamErrorType.RETRY_EXHAUSTED,
                        "downstream dependency '" + dependency + "' failed after retries",
                        last.isUncertain(), last);
            }
            throw last;
        } finally {
            rt.bulkhead().release();
        }
    }

    private boolean retryable(DownstreamException ex) {
        return ex.getErrorType() == DownstreamErrorType.TIMEOUT
                || ex.getErrorType() == DownstreamErrorType.REMOTE_ERROR;
    }

    private <T> AttemptResult<T> runAttempt(DependencyRuntime rt, String dependency, Callable<T> call,
                                            int attempt, String traceId) {
        // 传播 MDC 到依赖线程，保证下游日志可关联同一 traceId
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        Callable<T> wrapped = () -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                return call.call();
            } finally {
                MDC.clear();
            }
        };

        Future<T> future = rt.executor().submit(wrapped);
        try {
            T value = future.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return AttemptResult.success(value);
        } catch (TimeoutException e) {
            future.cancel(true); // 中断依赖线程
            awaitTermination(future); // 等待其响应中断退出，避免隔离槽位被僵尸任务长期占用
            log.warn("[{}] attempt timeout dependency={} attempt={} timeout={}",
                    traceId, dependency, attempt, properties.getTimeout());
            return AttemptResult.failure(new DownstreamException(DownstreamErrorType.TIMEOUT,
                    "downstream dependency '" + dependency + "' timed out", true));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return AttemptResult.failure(new DownstreamException(DownstreamErrorType.TIMEOUT,
                    "downstream call interrupted", true));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return AttemptResult.failure(mapCause(dependency, cause, traceId, attempt));
        }
    }

    private DownstreamException mapCause(String dependency, Throwable cause, String traceId, int attempt) {
        // 业务异常穿透：不属于下游失败，不计熔断，不由本层包装为下游错误
        if (cause instanceof BusinessException be) {
            log.info("[{}] deterministic business rejection from dependency={} attempt={} code={}",
                    traceId, dependency, attempt, be.getBusinessErrorCode());
            throw be;
        }
        if (cause instanceof DownstreamException de) {
            return de;
        }
        // 其他异常视为远端错误；参数校验类异常副作用确定未发生，其余按“可能已发生”处理
        boolean uncertain = !(cause instanceof IllegalArgumentException);
        log.warn("[{}] downstream error dependency={} attempt={} type={}",
                traceId, dependency, attempt, cause.getClass().getSimpleName());
        return new DownstreamException(DownstreamErrorType.REMOTE_ERROR,
                "downstream dependency '" + dependency + "' returned an error", uncertain);
    }

    private void sleepBackoff(int attempt) {
        try {
            long millis = properties.getRetryBackoff().toMillis() * (1L << Math.min(attempt - 1, 10));
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 超时取消后，短暂等待执行线程真正终止（任务应响应中断）。
     * 等待时长以超时时长为上限，避免调用线程被不响应中断的任务无限拖住；
     * 即使等待超时，信号量槽位也会在 finally 中释放（容量保护优先于绝对精确的在途计数）。
     */
    private void awaitTermination(Future<?> future) {
        try {
            future.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            // 任务未在宽限期内响应中断：放弃等待，记录诊断
            log.warn("downstream task did not finish after cancellation within grace period");
        } catch (Exception ignored) {
            // 取消/执行异常均无需再处理
        }
    }

    /** 暴露熔断器状态（测试与监控使用）。 */
    public CircuitBreakerState circuitState(String dependency) {
        return runtime(dependency).breaker().currentState();
    }

    /** 重置指定依赖的熔断器统计与状态（测试与运维干预使用）。 */
    public void resetCircuit(String dependency) {
        runtime(dependency).breaker().reset();
    }

    private record AttemptResult<T>(boolean success, T value, DownstreamException failure) {
        static <T> AttemptResult<T> success(T value) {
            return new AttemptResult<>(true, value, null);
        }

        static <T> AttemptResult<T> failure(DownstreamException failure) {
            return new AttemptResult<>(false, null, failure);
        }
    }
}
