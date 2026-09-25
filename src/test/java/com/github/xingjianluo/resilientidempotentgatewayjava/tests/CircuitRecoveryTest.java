package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandRequest;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandResult;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandService;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.SimulatedPaymentClient;
import com.github.xingjianluo.resilientidempotentgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilienceEngine;
import com.github.xingjianluo.resilientidempotentgatewayjava.tests.support.TestLog;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 熔断恢复链路：连续失败跳闸 -> OPEN 期间快速失败 ->
 * 冷却后试探失败则重新 OPEN -> 再冷却试探成功才 CLOSED。
 * 每个请求使用不同幂等键，避免幂等收敛干扰熔断判定。
 */
class CircuitRecoveryTest {

    private ExecutorService executor;
    private InMemoryIdempotencyStore store;
    private SimulatedPaymentClient client;
    private ResilienceEngine engine;
    private CommandService service;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties();
        props.getIdempotency().setTtlSeconds(60);
        props.getIdempotency().setAcquireWaitMillis(1000);

        GatewayProperties.Rule payment = new GatewayProperties.Rule();
        payment.setCallTimeoutMillis(1000);
        payment.setMaxAttempts(1); // 每个请求只尝试一次，便于精确数失败次数
        payment.setBackoffMillis(1);
        payment.setFailureThreshold(3);
        payment.setOpenMillis(500);
        payment.setBulkheadMaxConcurrent(4);
        props.getResilience().getDependencies().put("payment", payment);

        executor = Executors.newVirtualThreadPerTaskExecutor();
        store = new InMemoryIdempotencyStore(props);
        client = new SimulatedPaymentClient();
        engine = new ResilienceEngine(executor, props);
        service = new CommandService(store, engine, client, props);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void openThenFailedProbeThenSuccessfulProbe() throws InterruptedException {
        TestLog.section("熔断恢复：失败跳闸、试探失败重开、试探成功关闭");

        // 1) 连续 3 个确定性业务失败使熔断器跳闸
        for (int i = 1; i <= 3; i++) {
            CommandResult r = service.execute("caller", "FAIL-" + i,
                    new CommandRequest("business-error", "ORD-" + i, 10, null), null);
            TestLog.transition("请求 %d -> http=%d code=%s circuit=%s".formatted(
                    i, r.httpStatus(), r.code(), engine.circuitState("payment")));
            assertEquals(422, r.httpStatus());
        }
        TestLog.basis("3 次连续失败后熔断器应为 OPEN");
        assertEquals(com.github.xingjianluo.resilientidempotentgatewayjava.resilience.CircuitState.OPEN,
                engine.circuitState("payment"));

        // 2) OPEN 期间请求被快速拒绝，错误码可区分，且不触达下游
        int callsBefore = totalExecutions();
        CommandResult blocked = service.execute("caller", "BLOCKED",
                new CommandRequest("success", "ORD-BLOCKED", 10, null), null);
        TestLog.basis("OPEN 期间新请求 -> 502 DOWNSTREAM_CIRCUIT_OPEN，不触达下游");
        assertEquals(502, blocked.httpStatus());
        assertEquals("DOWNSTREAM_CIRCUIT_OPEN", blocked.code());
        assertEquals(callsBefore, totalExecutions(), "熔断拒绝不得产生下游调用");

        // 3) 冷却结束，第一个请求成为试探；继续失败 -> 重新 OPEN
        Thread.sleep(550);
        CommandResult failedProbe = service.execute("caller", "PROBE-FAIL",
                new CommandRequest("business-error", "ORD-PF", 10, null), null);
        TestLog.transition("冷却后试探失败 -> http=%d, circuit 重新 OPEN".formatted(failedProbe.httpStatus()));
        assertEquals(422, failedProbe.httpStatus());
        assertEquals(com.github.xingjianluo.resilientidempotentgatewayjava.resilience.CircuitState.OPEN,
                engine.circuitState("payment"), "试探失败必须重新 OPEN");

        // 4) 冷却不足，仍然拒绝
        Thread.sleep(200);
        CommandResult stillBlocked = service.execute("caller", "STILL-BLOCKED",
                new CommandRequest("success", "ORD-SB", 10, null), null);
        assertEquals(502, stillBlocked.httpStatus());

        // 5) 重新冷却结束，试探成功 -> CLOSED
        Thread.sleep(350);
        CommandResult successProbe = service.execute("caller", "PROBE-OK",
                new CommandRequest("success", "ORD-POK", 10, null), null);
        TestLog.transition("再次冷却后试探成功 -> http=%d, circuit CLOSED".formatted(successProbe.httpStatus()));
        assertEquals(200, successProbe.httpStatus());
        assertEquals(com.github.xingjianluo.resilientidempotentgatewayjava.resilience.CircuitState.CLOSED,
                engine.circuitState("payment"), "试探成功必须关闭熔断");

        // 6) 关闭后普通请求恢复正常
        CommandResult normal = service.execute("caller", "NORMAL",
                new CommandRequest("success", "ORD-N", 10, null), null);
        assertEquals(200, normal.httpStatus());
    }

    private int totalExecutions() {
        int total = 0;
        for (int i = 1; i <= 3; i++) {
            total += client.executionCountOf("ORD-" + i);
        }
        total += client.executionCountOf("ORD-PF");
        total += client.executionCountOf("ORD-POK");
        total += client.executionCountOf("ORD-BLOCKED");
        total += client.executionCountOf("ORD-SB");
        total += client.executionCountOf("ORD-N");
        return total;
    }
}
