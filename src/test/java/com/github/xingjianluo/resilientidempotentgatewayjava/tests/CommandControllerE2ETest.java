package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import com.github.xingjianluo.resilientidempotentgatewayjava.demo.DownstreamTransferClient;
import com.github.xingjianluo.resilientidempotentgatewayjava.demo.TransferRequest;
import com.github.xingjianluo.resilientidempotentgatewayjava.demo.TransferResult;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.BusinessException;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamErrorType;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端 HTTP 链路测试：
 * 幂等收敛 / 载荷不一致 / 键过期 / 存储不可用 / 下游超时(不确定) /
 * 业务失败 / 敏感信息不出现在日志与响应中 / traceId 贯穿与回写。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommandControllerE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DownstreamTransferClient client;

    @Autowired
    private com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.InMemoryIdempotencyStore store;

    @Autowired
    private com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilientCaller resilientCaller;

    @BeforeEach
    void resetStore() {
        store.setAvailable(true);
        // 多个 E2E 类共享同一 Spring 上下文，复位熔断器避免超时用例的失败统计跨类累积
        resilientCaller.resetCircuit(
                com.github.xingjianluo.resilientidempotentgatewayjava.demo.TransferService.DEPENDENCY_LEDGER);
    }

    private static final String BODY =
            "{\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amountCents\":100,\"credential\":\"super-secret-token\"}";
    private static final String BODY_DIFFERENT =
            "{\"fromAccount\":\"A\",\"toAccount\":\"C\",\"amountCents\":100,\"credential\":\"super-secret-token\"}";

    @Test
    @DisplayName("成功路径 + 重复提交：副作用一次，第二次 replayed=true 且结果稳定")
    void successThenDuplicateReplay() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(client.transfer(any(TransferRequest.class), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return new TransferResult("TX-1", "COMPLETED", 100);
        });

        MvcResult first = mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "caller-1")
                        .header("Idempotency-Key", "e2e-key-1")
                        .header("X-Trace-Id", "trace-success")
                        .contentType("application/json")
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trace-success"))
                .andExpect(jsonPath("$.idempotencyStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.result.transferId").value("TX-1"))
                .andReturn();

        MvcResult second = mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "caller-1")
                        .header("Idempotency-Key", "e2e-key-1")
                        .contentType("application/json")
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.result.transferId").value("TX-1"))
                .andReturn();

        System.out.println("[输入] 同键两次转账；[判定依据] 第二次命中 SUCCEEDED 终态；[状态迁移] 执行1次，重放1次");
        assertEquals(1, calls.get(), "下游副作用只能发生一次");
        assertFalse(first.getResponse().getContentAsString().contains("super-secret-token"),
                "响应中不得出现凭据原文");
        assertFalse(second.getResponse().getContentAsString().contains("super-secret-token"));
    }

    @Test
    @DisplayName("同键不同载荷：422 PAYLOAD_MISMATCH，下游不被调用")
    void payloadMismatchRejected() throws Exception {
        when(client.transfer(any(), anyString())).thenReturn(new TransferResult("X", "COMPLETED", 100));

        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "c").header("Idempotency-Key", "k-pm")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "c").header("Idempotency-Key", "k-pm")
                        .contentType("application/json").content(BODY_DIFFERENT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        verify(client, times(1)).transfer(any(), anyString());
        System.out.println("[输入] 同键先 BODY 后 BODY_DIFFERENT；[判定依据] 载荷摘要不一致；[结论] 422 PAYLOAD_MISMATCH");
    }

    @Test
    @DisplayName("并发重复提交：同键并发只有一个 200，另一个 409 CONCURRENT_IN_FLIGHT")
    void concurrentDuplicatesOneSucceedsOneRejected() throws Exception {
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(client.transfer(any(), anyString())).thenAnswer(inv -> {
            inside.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new TransferResult("TX-CONC", "COMPLETED", 100);
        });

        ExecutorServiceShim pool = new ExecutorServiceShim(2);
        java.util.concurrent.Future<MvcResult> f1 = pool.submit(() -> mockMvc.perform(post("/commands/transfers")
                .header("X-Caller-Id", "c").header("Idempotency-Key", "k-conc")
                .contentType("application/json").content(BODY)).andReturn());
        assertTrue(inside.await(2, java.util.concurrent.TimeUnit.SECONDS));
        Thread.sleep(100);

        java.util.concurrent.Future<MvcResult> f2 = pool.submit(() -> mockMvc.perform(post("/commands/transfers")
                .header("X-Caller-Id", "c").header("Idempotency-Key", "k-conc")
                .contentType("application/json").content(BODY)).andReturn());

        MvcResult r2 = f2.get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(409, r2.getResponse().getStatus());
        assertTrue(r2.getResponse().getContentAsString().contains("IDEMPOTENCY_CONCURRENT_IN_FLIGHT"));

        release.countDown();
        MvcResult r1 = f1.get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, r1.getResponse().getStatus());

        verify(client, times(1)).transfer(any(), anyString());
        pool.shutdown();
        System.out.println("[输入] 两线程同键并发（首个阻塞）；[判定依据] claim 原子胜负；"
                + "[结论] 一个 200 SUCCEEDED，一个 409 CONCURRENT_IN_FLIGHT，下游仅 1 次");
    }

    @Test
    @DisplayName("存储不可用：503 STORE_UNAVAILABLE，fail-closed 不调用下游")
    void storeUnavailableReturns503() throws Exception {
        store.setAvailable(false);
        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "c").header("Idempotency-Key", "k-down")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_STORE_UNAVAILABLE"));
        verifyNoInteractions(client);
        System.out.println("[输入] 存储不可用时发请求；[判定依据] 读取即失败 fail-closed；[结论] 503，无任何下游调用");
    }

    @Test
    @DisplayName("下游超时：业务标记 UNCERTAIN(202)，重复提交重放不确定结论而非重试")
    void downstreamTimeoutMarksUncertainAndReplays() throws Exception {
        when(client.transfer(any(), anyString())).thenAnswer(inv -> {
            Thread.sleep(1000);
            return new TransferResult("X", "COMPLETED", 100);
        });

        MvcResult first = mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "c").header("Idempotency-Key", "k-timeout")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.idempotencyStatus").value("UNCERTAIN"))
                .andExpect(jsonPath("$.errorCode").value("DOWNSTREAM_TIMEOUT"))
                .andReturn();

        MvcResult second = mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "c").header("Idempotency-Key", "k-timeout")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.idempotencyStatus").value("UNCERTAIN"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.errorCode").value("DOWNSTREAM_TIMEOUT"))
                .andReturn();

        System.out.println("[输入] 下游超时后重复提交；[判定依据] 首次 UNCERTAIN 落库；[状态] 第二次重放 UNCERTAIN，不重试");
        verify(client, times(1)).transfer(any(), anyString());
        assertFalse(first.getResponse().getContentAsString().contains("super-secret-token"));
        assertFalse(second.getResponse().getContentAsString().contains("super-secret-token"));
    }

    @Test
    @DisplayName("业务校验失败与下游失败相区分：422 FAILED/INVALID_AMOUNT，且可稳定重放")
    void businessValidationFailureIsDistinctAndReplayed() throws Exception {
        String badBody = "{\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amountCents\":-5,\"credential\":\"t\"}";

        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "c").header("Idempotency-Key", "k-biz")
                        .contentType("application/json").content(badBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.idempotencyStatus").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("INVALID_AMOUNT"));

        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "c").header("Idempotency-Key", "k-biz")
                        .contentType("application/json").content(badBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.errorCode").value("INVALID_AMOUNT"));

        verifyNoInteractions(client);
        System.out.println("[输入] amount=-5 的确定性业务失败后重复提交；[判定依据] 参数校验先于下游；"
                + "[状态] 两次均 422 FAILED/INVALID_AMOUNT，第二次 replayed，不触达下游");
    }

    @Test
    @DisplayName("下游确定性业务拒绝(余额不足)：FAILED 并稳定重放")
    void downstreamBusinessRejectionIsDeterministicFailure() throws Exception {
        when(client.transfer(any(), anyString()))
                .thenThrow(new BusinessException("INSUFFICIENT_FUNDS", "not enough"));

        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "c").header("Idempotency-Key", "k-funds")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.idempotencyStatus").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));

        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "c").header("Idempotency-Key", "k-funds")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.replayed").value(true));

        verify(client, times(1)).transfer(any(), anyString());
        System.out.println("[输入] 下游抛 BusinessException(INSUFFICIENT_FUNDS) 后重复提交；"
                + "[判定依据] 确定性业务拒绝->FAILED；[状态] 重放且下游仅调用一次");
    }

    /** 极简线程池包装，避免在测试里到处写 ExecutorService。 */
    private static final class ExecutorServiceShim {
        private final java.util.concurrent.ExecutorService es;

        ExecutorServiceShim(int n) {
            this.es = java.util.concurrent.Executors.newFixedThreadPool(n);
        }

        <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            return es.submit(task);
        }

        void shutdown() {
            es.shutdownNow();
        }
    }
}
