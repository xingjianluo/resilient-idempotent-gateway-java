package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandRequest;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandResult;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandService;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.SimulatedPaymentClient;
import com.github.xingjianluo.resilientidempotentgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotentRecord;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.RequestStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilienceEngine;
import com.github.xingjianluo.resilientidempotentgatewayjava.tests.support.TestLog;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 命令入口端到端（不启动 Web 容器，纯本地）：
 * 成功收敛、超时与业务失败可区分、部分成功标记不确定且不重复副作用、存储不可用降级、敏感信息不外泄。
 */
class CommandServiceTest {

    private ExecutorService executor;
    private InMemoryIdempotencyStore store;
    private SimulatedPaymentClient client;
    private CommandService service;
    private GatewayProperties props;

    @BeforeEach
    void setUp() {
        props = new GatewayProperties();
        props.getIdempotency().setTtlSeconds(60);
        props.getIdempotency().setAcquireWaitMillis(3000);

        GatewayProperties.Rule payment = new GatewayProperties.Rule();
        payment.setCallTimeoutMillis(150);
        payment.setMaxAttempts(3);
        payment.setBackoffMillis(2);
        payment.setFailureThreshold(5);
        payment.setOpenMillis(1000);
        payment.setBulkheadMaxConcurrent(8);
        props.getResilience().getDependencies().put("payment", payment);

        executor = Executors.newVirtualThreadPerTaskExecutor();
        store = new InMemoryIdempotencyStore(props);
        client = new SimulatedPaymentClient();
        client.setTimeoutBlockMillis(400); // 明显超过 150ms 超时预算
        ResilienceEngine engine = new ResilienceEngine(executor, props);
        service = new CommandService(store, engine, client, props);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private CommandRequest req(String scenario, String orderId) {
        return new CommandRequest(scenario, orderId, 100, "super-secret-credential");
    }

    @Test
    void successThenDuplicateReturnsStableResultAndSingleSideEffect() {
        TestLog.section("成功 + 重复提交：结果稳定，副作用一次");
        CommandRequest request = req("success", "ORD-1");
        CommandResult first = service.execute("caller", "K1", request, null);
        TestLog.basis("首次应 200 OK 且下游执行 1 次");
        assertEquals(200, first.httpStatus());
        assertEquals("OK", first.code());
        assertEquals(1, client.executionCountOf("ORD-1"));

        CommandResult duplicate = service.execute("caller", "K1", request, null);
        TestLog.basis("重复请求 replay=true，返回稳定结论，不再触达下游");
        assertEquals(200, duplicate.httpStatus());
        assertTrue(duplicate.replay());
        assertEquals(1, client.executionCountOf("ORD-1"), "副作用必须只发生一次");
        assertEquals(first.traceId(), first.traceId());
        assertNotEquals(first.traceId(), duplicate.traceId(), "每次请求仍有独立 traceId");
    }

    @Test
    void timeoutAndBusinessFailureAreDistinguishable() {
        TestLog.section("下游超时 vs 业务失败：错误码与终态均不同");
        CommandResult timeout = service.execute("caller", "KT", req("timeout", "ORD-T"), null);
        TestLog.basis("超时=504 DOWNSTREAM_TIMEOUT，结果未知");
        assertEquals(504, timeout.httpStatus());
        assertEquals("DOWNSTREAM_TIMEOUT", timeout.code());

        CommandResult business = service.execute("caller", "KB", req("business-error", "ORD-B"), null);
        TestLog.basis("业务失败=422 BUSINESS_REJECTED，结果确定");
        assertEquals(422, business.httpStatus());
        assertEquals("BUSINESS_REJECTED", business.code());

        Optional<IdempotentRecord> tRec = store.peek("caller", "KT");
        Optional<IdempotentRecord> bRec = store.peek("caller", "KB");
        TestLog.transition("超时记录状态=%s, 业务失败记录状态=%s".formatted(
                tRec.map(IdempotentRecord::getStatus).orElse(null),
                bRec.map(IdempotentRecord::getStatus).orElse(null)));
        assertEquals(RequestStatus.UNCERTAIN, tRec.orElseThrow().getStatus());
        assertEquals(RequestStatus.FAILED, bRec.orElseThrow().getStatus());
        assertEquals(1, client.executionCountOf("ORD-T"), "超时不重试");
        assertEquals(1, client.executionCountOf("ORD-B"), "业务失败不重试");
    }

    @Test
    void partialSuccessIsMarkedUncertainAndRetryDoesNotReplayOrRepeat() {
        TestLog.section("部分成功：超时后标记 UNCERTAIN，再次同键不静默重试");
        CommandResult first = service.execute("caller", "KU",
                req("timeout-then-success", "ORD-U"), null);
        TestLog.basis("首次请求在下游可能已扣款后超时 => 504 + UNCERTAIN");
        assertEquals(504, first.httpStatus());
        assertEquals(RequestStatus.UNCERTAIN, store.peek("caller", "KU").orElseThrow().getStatus());
        assertEquals(1, client.executionCountOf("ORD-U"), "不确定时不得自动重试触达下游");

        CommandResult again = service.execute("caller", "KU",
                req("timeout-then-success", "ORD-U"), null);
        TestLog.basis("同键重放命中 UNCERTAIN => 409 RESULT_UNCERTAIN，要求对账而非重放");
        assertEquals(409, again.httpStatus());
        assertEquals("RESULT_UNCERTAIN", again.code());
        assertTrue(again.message().contains("reconcile"));
        assertEquals(1, client.executionCountOf("ORD-U"), "仍然没有第二次下游调用");
    }

    @Test
    void failedBusinessRequestIsReplayedNotReExecuted() {
        TestLog.section("失败重放：确定性失败的重复请求回放原结论");
        service.execute("caller", "KF", req("business-error", "ORD-F"), null);
        CommandResult replay = service.execute("caller", "KF", req("business-error", "ORD-F"), null);
        assertEquals(422, replay.httpStatus());
        assertEquals("BUSINESS_REJECTED", replay.code());
        assertTrue(replay.replay());
        assertEquals(1, client.executionCountOf("ORD-F"));
    }

    @Test
    void flakyDownstreamSucceedsWithinRetryBudget() {
        TestLog.section("瞬时失败：可重试错误在预算内成功，只对外返回一次成功");
        client.setFlakyFailures(2);
        CommandResult result = service.execute("caller", "KR", req("flaky", "ORD-R"), null);
        TestLog.basis("maxAttempts=3，前两次瞬时失败，第三次成功");
        assertEquals(200, result.httpStatus());
        assertEquals(3, client.executionCountOf("ORD-R"));
        assertEquals(RequestStatus.SUCCESS, store.peek("caller", "KR").orElseThrow().getStatus());
    }

    @Test
    void payloadMismatchRejectedWithoutSideEffect() {
        TestLog.section("同键不同载荷：拒绝且不执行");
        service.execute("caller", "KP", req("success", "ORD-P1"), null);
        CommandResult mismatch = service.execute("caller", "KP", req("success", "ORD-P2"), null);
        assertEquals(409, mismatch.httpStatus());
        assertEquals("PAYLOAD_MISMATCH", mismatch.code());
        assertEquals(0, client.executionCountOf("ORD-P2"));
    }

    @Test
    void storeOutageFailsClosed() {
        TestLog.section("存储暂时不可用：503 降级，副作用不发生");
        store.setAvailable(false);
        CommandResult result = service.execute("caller", "KO", req("success", "ORD-O"), null);
        assertEquals(503, result.httpStatus());
        assertEquals("STORE_UNAVAILABLE", result.code());
        assertEquals(0, client.executionCountOf("ORD-O"), "存储不可用时禁止执行下游");
        assertNull(result.data());
    }

    @Test
    void credentialsNeverAppearInResponseOrStatus() {
        TestLog.section("敏感信息：凭据不出现在对外结论");
        CommandResult result = service.execute("caller", "KS", req("business-error", "ORD-S"), null);
        String serialized = String.valueOf(result) + String.valueOf(result.data()) + result.message();
        assertFalse(serialized.contains("super-secret-credential"),
                "凭据绝不能出现在响应中");
    }
}
