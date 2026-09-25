package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次调用的超时控制。
 * <p>
 * 使用独立的守护线程调度器执行任务，并在超时时中断执行线程。该组件与重试、
 * 熔断、并发隔离相互独立：它只负责“这一次尝试”是否在时限内完成。
 */
@Component
public class TimeoutGuard implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TimeoutGuard.class);

    private final ExecutorService executor;

    public TimeoutGuard() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "downstream-timeout-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newCachedThreadPool(factory);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    /**
     * 在超时约束下执行一次任务。
     * <ul>
     *     <li>{@code completed=true}：正常返回，value/error 二者之一非空；</li>
     *     <li>{@code completed=false}：超时，执行线程已收到中断。</li>
     * </ul>
     */
    public <T> TimedResult<T> run(Callable<T> task, Duration timeout) {
        Future<T> future = executor.submit(task);
        try {
            T value = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new TimedResult<>(true, value, null);
        } catch (TimeoutException e) {
            future.cancel(true); // 中断执行线程，避免泄漏
            log.debug("downstream attempt timed out after {}", timeout);
            return new TimedResult<>(false, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TimedResult<>(false, null, e);
        } catch (ExecutionException e) {
            return new TimedResult<>(true, null, e.getCause() != null ? e.getCause() : e);
        }
    }

    /** 带超时的执行结果包装。 */
    public record TimedResult<T>(boolean completed, T value, Throwable error) {
    }
}
