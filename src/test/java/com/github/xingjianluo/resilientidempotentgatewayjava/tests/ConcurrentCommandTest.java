package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandRequest;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandResult;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandService;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.SimulatedPaymentClient;
import com.github.xingjianluo.resilientidempotentgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.RequestStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilienceEngine;
import com.github.xingjianluo.resilientidempotentgatewayjava.tests.support.TestLog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 高并发同键重复提交：副作用恰好一次，每个请求都得到可解释且互不矛盾的结论。 */
class ConcurrentCommandTest {

    private ExecutorService executor;
    private InMemoryIdempotencyStore store;
    private SimulatedPaymentClient client;
    private CommandService service;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties();
        props.getIdempotency().setTtlSeconds(60);
        props.getIdempotency().setAcquireWaitMillis(5000);

        GatewayProperties.Rule payment = new GatewayProperties.Rule();
        payment.setCallTimeoutMillis(2000);
        payment.setMaxAttempts(1);
        payment.setFailureThreshold(5);
        payment.setOpenMillis(1000);
        payment.setBulkheadMaxConcurrent(16);
        props.getResilience().getDependencies().put("payment", payment);

        executor = Executors.newVirtualThreadPerTaskExecutor();
        store = new InMemoryIdempotencyStore(props);
        client = new SimulatedPaymentClient();
        service = new CommandService(store, new ResilienceEngine(executor, props), client, props);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void manyConcurrentIdenticalRequestsProduceOneSideEffect() throws Exception {
        TestLog.section("并发 24 个完全相同请求");
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gun = new CountDownLatch(1);
        AtomicInteger firstOk = new AtomicInteger();
        AtomicInteger replayOk = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<String> codes = java.util.Collections.synchronizedList(new ArrayList<>());

        CallableTask.register();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    gun.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                CommandResult r = service.execute("caller-concurrent", "HOT",
                        new CommandRequest("success", "ORD-HOT", 7, "secret"), null);
                codes.add(r.code());
                if (r.httpStatus() == 200 && !r.replay()) {
                    firstOk.incrementAndGet();
                } else if (r.httpStatus() == 200 && r.replay()) {
                    replayOk.incrementAndGet();
                } else if (r.httpStatus() == 409 && "CONCURRENT_SUBMISSION".equals(r.code())) {
                    conflicts.incrementAndGet();
                } else {
                    Assertions.fail("unexpected result: " + r.httpStatus() + " " + r.code());
                }
            }));
        }
        gun.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        TestLog.transition("首次成功=%d, 重放成功=%d, 并发冲突=%d".formatted(
                firstOk.get(), replayOk.get(), conflicts.get()));
        TestLog.basis("下游副作用计数必须为 1；其余请求要么重放同一结果要么显式并发冲突");
        assertEquals(1, firstOk.get());
        assertEquals(1, client.executionCountOf("ORD-HOT"), "副作用恰好一次");
        assertEquals(threads - 1, replayOk.get() + conflicts.get());
        assertEquals(RequestStatus.SUCCESS, store.peek("caller-concurrent", "HOT").orElseThrow().getStatus());
    }

    @Test
    void concurrentRequestsWithDifferentPayloadsNeverExecuteSecondPayload() throws Exception {
        TestLog.section("并发不同载荷：赢者执行，败者得到可区分拒绝");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gun = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    gun.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // 每个线程载荷不同，但抢占同一个幂等键
                CommandResult r = service.execute("caller-diff", "SAME-KEY",
                        new CommandRequest("success", "ORD-DIFF-" + idx, idx, "secret"), null);
                if (r.httpStatus() == 200 && !r.replay()) {
                    accepted.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                    assertTrue(r.httpStatus() == 409, "拒绝码必须是 409，实际 " + r.httpStatus());
                    assertFalse("OK".equals(r.code()));
                }
            }));
        }
        gun.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        TestLog.transition("accepted=%d rejected=%d".formatted(accepted.get(), rejected.get()));
        assertEquals(1, accepted.get());
        assertEquals(threads - 1, rejected.get());
    }

    /** 占位，保持导入整洁。 */
    static final class CallableTask {
        static void register() {
        }
    }
}
