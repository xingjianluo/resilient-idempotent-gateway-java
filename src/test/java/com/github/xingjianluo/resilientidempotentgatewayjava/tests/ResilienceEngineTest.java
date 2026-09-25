package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.resilientidempotentgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.CircuitState;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamErrorType;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamException;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamOutcome;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilienceEngine;
import com.github.xingjianluo.resilientidempotentgatewayjava.tests.support.TestLog;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 隔离/超时/重试/熔断四类策略的行为与互不干扰验证。 */
class ResilienceEngineTest {

    private ExecutorService executor;

    private GatewayProperties properties(long timeoutMs, int maxAttempts, int threshold,
                                         long openMs, int bulkhead) {
        GatewayProperties p = new GatewayProperties();
        GatewayProperties.Rule rule = new GatewayProperties.Rule();
        rule.setCallTimeoutMillis(timeoutMs);
        rule.setMaxAttempts(maxAttempts);
        rule.setBackoffMillis(1);
        rule.setFailureThreshold(threshold);
        rule.setOpenMillis(openMs);
        rule.setBulkheadMaxConcurrent(bulkhead);
        p.getResilience().getDependencies().put("payment", rule);
        return p;
    }

    private ResilienceEngine engine(GatewayProperties p) {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        return new ResilienceEngine(executor, p);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void timeoutIsNotRetriedAndMarkedUncertain() {
        TestLog.section("超时：不重试，结论 outcomeKnown=false");
        GatewayProperties p = properties(60, 3, 5, 1000, 10);
        ResilienceEngine engine = engine(p);
        AtomicInteger calls = new AtomicInteger();

        TestLog.input("超时预算=60ms, 下游阻塞 300ms, 最大尝试=3");
        long start = System.currentTimeMillis();
        DownstreamOutcome<String> outcome = engine.execute("payment", () -> {
            calls.incrementAndGet();
            Thread.sleep(300);
            return "never";
        });
        long elapsed = System.currentTimeMillis() - start;

        TestLog.basis("超时后副作用未知 => errorType=TIMEOUT, outcomeKnown=false, 且只尝试 1 次");
        assertFalse(outcome.success());
        assertEquals(DownstreamErrorType.TIMEOUT, outcome.errorType());
        assertFalse(outcome.outcomeKnown(), "超时结果必须标记为未知");
        assertEquals(1, outcome.attempts(), "超时不得重试");
        assertEquals(1, calls.get(), "下游只应被调用一次");
        assertTrue(elapsed < 250, "应在超时预算附近返回，实际 " + elapsed + "ms");
    }

    @Test
    void businessRejectionIsNotRetriedAndDistinguishedFromTimeout() {
        TestLog.section("业务失败：确定性失败不重试，错误类型与超时可区分");
        GatewayProperties p = properties(500, 3, 5, 1000, 10);
        ResilienceEngine engine = engine(p);
        AtomicInteger calls = new AtomicInteger();

        DownstreamOutcome<String> outcome = engine.execute("payment", () -> {
            calls.incrementAndGet();
            throw new DownstreamException(DownstreamErrorType.BUSINESS_REJECTED,
                    "risk rejected", false, true);
        });

        TestLog.basis("BUSINESS_REJECTED + retryable=false => 不重试，保持原错误类型");
        assertFalse(outcome.success());
        assertEquals(DownstreamErrorType.BUSINESS_REJECTED, outcome.errorType());
        assertTrue(outcome.outcomeKnown());
        assertEquals(1, outcome.attempts());
        assertEquals(1, calls.get());
    }

    @Test
    void retryableFailureRetriesUntilExhaustedThenOpensCircuit() {
        TestLog.section("瞬时失败重试：耗尽后跳闸");
        GatewayProperties p = properties(500, 3, 2, 500, 10);
        ResilienceEngine engine = engine(p);
        AtomicInteger calls = new AtomicInteger();

        TestLog.input("maxAttempts=3, failureThreshold=2，下游恒定瞬时失败");
        DownstreamOutcome<String> first = engine.execute("payment", () -> {
            calls.incrementAndGet();
            throw new DownstreamException(DownstreamErrorType.DOWNSTREAM_ERROR,
                    "connection reset", true, true);
        });

        TestLog.basis("可重试且结果确定：共尝试 3 次后映射 RETRY_EXHAUSTED");
        assertEquals(DownstreamErrorType.RETRY_EXHAUSTED, first.errorType());
        assertEquals(3, first.attempts());
        assertEquals(3, calls.get());
        assertTrue(first.outcomeKnown());

        TestLog.transition("连续失败计数已达 2，第二个请求应直接被熔断拒绝");
        DownstreamOutcome<String> second = engine.execute("payment", () -> "ok");
        assertEquals(DownstreamErrorType.CIRCUIT_OPEN, second.errorType());
        assertEquals(0, second.attempts(), "熔断拒绝不应触达下游");
        assertEquals(3, calls.get(), "熔断期间副作用计数不变");
    }

    @Test
    void bulkheadRejectionDoesNotAffectCircuitOrOtherDependencies() {
        TestLog.section("隔离：信号量占满独立于熔断，且依赖间互不干扰");
        GatewayProperties p = properties(500, 1, 100, 1000, 1);
        ResilienceEngine engine = engine(p);
        // 给另一个依赖配置大隔离舱
        GatewayProperties.Rule other = new GatewayProperties.Rule();
        other.setCallTimeoutMillis(500);
        other.setBulkheadMaxConcurrent(8);
        p.getResilience().getDependencies().put("inventory", other);

        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        Thread holder = new Thread(() -> engine.execute("payment", () -> {
            entered.countDown();
            release.await();
            return "held";
        }), "holder");
        holder.start();
        try {
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));

            DownstreamOutcome<String> rejected = engine.execute("payment", () -> "blocked");
            TestLog.basis("payment 并发=1 已占用 => BULKHEAD_FULL，不改变熔断");
            assertEquals(DownstreamErrorType.BULKHEAD_FULL, rejected.errorType());
            assertEquals(CircuitState.CLOSED, engine.circuitState("payment"),
                    "被隔离的请求未发出，不应计入熔断失败");

            DownstreamOutcome<String> otherOk = engine.execute("inventory", () -> "inventory-ok");
            TestLog.basis("inventory 有独立隔离舱，应正常成功");
            assertTrue(otherOk.success());
            assertEquals("inventory-ok", otherOk.value());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        } finally {
            release.countDown();
            try {
                holder.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
