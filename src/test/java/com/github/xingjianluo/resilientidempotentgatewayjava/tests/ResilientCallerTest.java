package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.BusinessException;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.CircuitBreakerState;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamErrorType;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamException;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilienceProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilientCaller;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.TimeoutGuard;
import com.github.xingjianluo.resilientidempotentgatewayjava.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下游隔离与降级链路测试。全部使用本地可控假实现，无外部服务。
 */
class ResilientCallerTest {

    private ResilienceProperties props;
    private ResilientCaller caller;

    @BeforeEach
    void setUp() {
        props = new ResilienceProperties();
        props.setTimeout(Duration.ofMillis(200));
        props.setMaxAttempts(1);
        props.setRetryBackoff(Duration.ofMillis(10));
        props.setCircuitMinimumCalls(5);
        props.setCircuitFailureRateThreshold(0.5);
        props.setCircuitOpenDuration(Duration.ofMillis(300));
        props.setCircuitTrialRequests(2);
        props.setBulkheadMaxConcurrent(2);
        props.setExecutorPoolSize(4);
        caller = new ResilientCaller(props, new TimeoutGuard());
        TraceContext.begin("resilience-test");
    }

    @AfterEach
    void tearDown() {
        TraceContext.end();
    }

    private DownstreamException expectDownstream(Runnable r) {
        return assertThrows(DownstreamException.class, r::run);
    }

    @Test
    @DisplayName("下游超时：映射 TIMEOUT，且标记副作用不确定(uncertain)")
    void timeoutMapsToTimeoutAndUncertain() {
        DownstreamException ex = expectDownstream(() -> caller.call("slow-dep", () -> {
            Thread.sleep(1000);
            return "x";
        }));
        System.out.println("[输入] 下游阻塞 1000ms，超时阈值 200ms；[判定依据] Future.get 超时并 cancel；"
                + "[结论] DOWNSTREAM_TIMEOUT / uncertain=true");
        assertEquals(DownstreamErrorType.TIMEOUT, ex.getErrorType());
        assertTrue(ex.isUncertain(), "超时意味着副作用未知，必须不确定");
        assertEquals(504, ex.getErrorType().httpStatus());
    }

    @Test
    @DisplayName("下游业务失败：BusinessException 穿透，不被包装为下游错误、不计熔断")
    void businessExceptionPassesThrough() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> caller.call("dep", () -> {
                    throw new BusinessException("INSUFFICIENT_FUNDS", "balance not enough");
                }));
        System.out.println("[输入] 下游抛 BusinessException(INSUFFICIENT_FUNDS)；[判定依据] mapCause 直接穿透；"
                + "[结论] 业务错误码保留，熔断器仍 CLOSED");
        assertEquals("INSUFFICIENT_FUNDS", ex.getBusinessErrorCode());
        assertEquals(CircuitBreakerState.CLOSED, caller.circuitState("dep"));
    }

    @Test
    @DisplayName("确定性远端错误：映射 REMOTE_ERROR；达到阈值后熔断打开")
    void remoteErrorOpensCircuitAfterThreshold() {
        props.setMaxAttempts(1);
        for (int i = 0; i < 5; i++) {
            DownstreamException ex = expectDownstream(() -> caller.call("flaky", () -> {
                throw new IllegalStateException("boom");
            }));
            assertEquals(DownstreamErrorType.REMOTE_ERROR, ex.getErrorType());
        }
        System.out.println("[输入] 连续 5 次远端错误（最小样本 5，阈值 0.5）；[判定依据] 失败率 100%；"
                + "[状态迁移] CLOSED -> OPEN");
        assertEquals(CircuitBreakerState.OPEN, caller.circuitState("flaky"));

        DownstreamException openEx = expectDownstream(() -> caller.call("flaky", () -> "ok"));
        assertEquals(DownstreamErrorType.CIRCUIT_OPEN, openEx.getErrorType());
        assertFalse(openEx.isUncertain(), "熔断打开时调用未发出，副作用确定未发生");
    }

    @Test
    @DisplayName("熔断恢复：冷却后试探成功连续达标 -> CLOSED；试探失败 -> 重新 OPEN")
    void circuitRecoveryDistinguishesTrialSuccessAndFailure() throws Exception {
        // 1) 打至 OPEN
        for (int i = 0; i < 5; i++) {
            expectDownstream(() -> caller.call("recov", () -> {
                throw new IllegalStateException("down");
            }));
        }
        assertEquals(CircuitBreakerState.OPEN, caller.circuitState("recov"));

        // 2) 冷却结束，第一个试探成功（需要连续 2 次成功才恢复）
        Thread.sleep(350);
        assertEquals("ok-1", caller.call("recov", () -> "ok-1"));
        assertEquals(CircuitBreakerState.HALF_OPEN, caller.circuitState("recov"),
                "仅 1 次试探成功，应保持 HALF_OPEN");

        // 试探在途期间，新请求应被快速拒绝
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<String> blocked = pool.submit(() -> caller.call("recov", () -> {
            started.countDown();
            finish.await(5, TimeUnit.SECONDS);
            return "ok-2";
        }));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        DownstreamException during = expectDownstream(() -> caller.call("recov", () -> "other"));
        assertEquals(DownstreamErrorType.CIRCUIT_OPEN, during.getErrorType(),
                "试探在途时其他请求被快速拒绝");
        finish.countDown();
        assertEquals("ok-2", blocked.get(2, TimeUnit.SECONDS));

        System.out.println("[输入] OPEN 冷却后连续两次试探成功；[判定依据] 试探成功数=trialRequests；"
                + "[状态迁移] OPEN -> HALF_OPEN -> CLOSED（恢复）");
        assertEquals(CircuitBreakerState.CLOSED, caller.circuitState("recov"));

        // 3) 再次打至 OPEN，冷却后试探失败应重新打开
        for (int i = 0; i < 5; i++) {
            expectDownstream(() -> caller.call("recov2", () -> {
                throw new IllegalStateException("down");
            }));
        }
        Thread.sleep(350);
        DownstreamException trialFail = expectDownstream(() -> caller.call("recov2", () -> {
            throw new IllegalStateException("still down");
        }));
        assertEquals(DownstreamErrorType.REMOTE_ERROR, trialFail.getErrorType());
        System.out.println("[输入] OPEN 冷却后试探请求失败；[判定依据] HALF_OPEN 任一失败；"
                + "[状态迁移] HALF_OPEN -> OPEN（继续冷却）");
        assertEquals(CircuitBreakerState.OPEN, caller.circuitState("recov2"));
        pool.shutdownNow();
    }

    @Test
    @DisplayName("重试：超时在 maxAttempts>1 时重试，耗尽后映射 RETRY_EXHAUSTED 且保留 uncertain")
    void retriesTimeoutThenExhausts() {
        props.setMaxAttempts(3);
        props.setRetryBackoff(Duration.ofMillis(5));
        props.setTimeout(Duration.ofMillis(80));
        AtomicInteger attempts = new AtomicInteger();
        DownstreamException ex = expectDownstream(() -> caller.call("always-slow", () -> {
            attempts.incrementAndGet();
            Thread.sleep(500);
            return "x";
        }));
        System.out.println("[输入] maxAttempts=3，每次都超时；[判定依据] TIMEOUT 可重试，3 次后耗尽；"
                + "[结论] RETRY_EXHAUSTED / uncertain=true，尝试次数=" + attempts.get());
        assertEquals(DownstreamErrorType.RETRY_EXHAUSTED, ex.getErrorType());
        assertTrue(ex.isUncertain());
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("重试：第 2 次成功后正常返回，熔断器记成功")
    void retrySucceedsOnSecondAttempt() {
        props.setMaxAttempts(3);
        props.setRetryBackoff(Duration.ofMillis(5));
        AtomicInteger attempts = new AtomicInteger();
        String result = caller.call("flaky-ok", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
            }
            return "done";
        });
        System.out.println("[输入] 首次远端错误、第二次成功；[判定依据] REMOTE_ERROR 可重试；"
                + "[状态] 熔断记成功，结果=done，尝试次数=" + attempts.get());
        assertEquals("done", result);
        assertEquals(2, attempts.get());
        assertEquals(CircuitBreakerState.CLOSED, caller.circuitState("flaky-ok"));
    }

    @Test
    @DisplayName("熔断打开类失败不重试，直接快速失败")
    void circuitOpenIsNotRetried() {
        for (int i = 0; i < 5; i++) {
            expectDownstream(() -> caller.call("locked", () -> {
                throw new IllegalStateException("down");
            }));
        }
        props.setMaxAttempts(3);
        AtomicInteger attempts = new AtomicInteger();
        DownstreamException ex = expectDownstream(() -> caller.call("locked", () -> {
            attempts.incrementAndGet();
            return "x";
        }));
        assertEquals(DownstreamErrorType.CIRCUIT_OPEN, ex.getErrorType());
        assertEquals(0, attempts.get(), "熔断打开时调用根本不应触达下游");
    }

    @Test
    @DisplayName("并发隔离：超过 bulkhead 容量的在途调用得到 BULKHEAD_FULL，且依赖间互不影响")
    void bulkheadRejectsExcessAndIsolatesDependencies() throws Exception {
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> caller.call("cap-dep", () -> {
                    inside.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return "ok";
                }));
            }
            assertTrue(inside.await(2, TimeUnit.SECONDS), "应占满 2 个槽位");

            DownstreamException ex = expectDownstream(() -> caller.call("cap-dep", () -> "ok"));
            System.out.println("[输入] bulkhead=2 且已有 2 个在途调用；[判定依据] tryAcquire 失败；"
                    + "[结论] BULKHEAD_FULL");
            assertEquals(DownstreamErrorType.BULKHEAD_FULL, ex.getErrorType());

            // 另一个依赖拥有独立隔离资源，不受影响
            assertEquals("independent", caller.call("other-dep", () -> "independent"));
            release.countDown();
        } finally {
            pool.shutdownNow();
        }
    }
}
