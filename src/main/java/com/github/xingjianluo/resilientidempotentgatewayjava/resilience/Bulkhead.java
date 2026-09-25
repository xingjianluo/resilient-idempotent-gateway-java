package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

import java.util.concurrent.Semaphore;

/**
 * 基于信号量的并发隔离。
 * <p>
 * 与线程池隔离互相独立、互不干扰：线程池限制可排队任务总量，
 * Bulkhead 限制单个依赖的在途调用数；槽位满时立即快速失败
 * （{@link DownstreamErrorType#BULKHEAD_FULL}），不阻塞调用线程。
 */
public class Bulkhead {

    private final Semaphore permits;
    private final int maxConcurrent;

    public Bulkhead(ResilienceProperties properties) {
        this.maxConcurrent = properties.getBulkheadMaxConcurrent();
        this.permits = new Semaphore(this.maxConcurrent, true);
    }

    /** 获取槽位，失败返回 false（不阻塞）。 */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }
}
