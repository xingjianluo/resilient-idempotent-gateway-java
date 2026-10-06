package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import com.github.xingjianluo.resilientidempotentgatewayjava.demo.DownstreamTransferClient;
import com.github.xingjianluo.resilientidempotentgatewayjava.demo.TransferRequest;
import com.github.xingjianluo.resilientidempotentgatewayjava.demo.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 对账回写端到端测试：HTTP 入口、闭环（未知 -> 回写 -> 稳定重放）、
 * 冲突响应、积压观测、敏感信息脱敏。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DownstreamTransferClient client;

    @Autowired
    private com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilientCaller resilientCaller;

    @org.junit.jupiter.api.BeforeEach
    void resetCircuit() {
        // 多个 E2E 类共享同一 Spring 上下文，复位熔断器避免超时用例的失败统计跨类累积
        resilientCaller.resetCircuit(
                com.github.xingjianluo.resilientidempotentgatewayjava.demo.TransferService.DEPENDENCY_LEDGER);
    }

    private static final String BODY =
            "{\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amountCents\":100,\"credential\":\"super-secret-token\"}";

    /** 让指定键走完“下游超时 -> UNCERTAIN”的链路。 */
    private void submitUncertain(String caller, String key) throws Exception {
        when(client.transfer(any(TransferRequest.class), anyString())).thenAnswer(inv -> {
            Thread.sleep(1000);
            return new TransferResult("X", "COMPLETED", 100);
        });
        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", caller)
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.idempotencyStatus").value("UNCERTAIN"));
    }

    @Test
    @DisplayName("闭环：下游超时回未知 -> 对账回写确认成功 -> 同键再提交返回稳定终态并标重放")
    void fullReconciliationLoopClosesUnknown() throws Exception {
        submitUncertain("e2e-caller", "e2e-loop");

        MvcResult rec = mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-caller")
                        .header("Idempotency-Key", "e2e-loop")
                        .header("X-Trace-Id", "trace-reconcile-1")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_SUCCEEDED\",\"reconciledBy\":\"ops-wang\","
                                + "\"reason\":\"ledger statement confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trace-reconcile-1"))
                .andExpect(jsonPath("$.idempotencyStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.reconciledBy").value("ops-wang"))
                .andExpect(jsonPath("$.reconciliationTraceId").value("trace-reconcile-1"))
                .andReturn();

        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "e2e-caller")
                        .header("Idempotency-Key", "e2e-loop")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.replayed").value(true));

        System.out.println("[输入] 超时回未知 -> 回写 CONFIRMED_SUCCEEDED -> 同键重提；"
                + "[判定依据] 回写落库为稳定终态；[状态迁移] UNCERTAIN->SUCCEEDED，重提 REPLAYED");
        assertFalse(rec.getResponse().getContentAsString().contains("super-secret-token"),
                "回写响应中不得出现凭据");
    }

    @Test
    @DisplayName("矛盾回写：409 RECONCILE_CONFLICT，响应带出胜出方信息")
    void conflictingReconcileReturnsWinner() throws Exception {
        submitUncertain("e2e-caller", "e2e-conflict");

        mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-caller")
                        .header("Idempotency-Key", "e2e-conflict")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_SUCCEEDED\",\"reconciledBy\":\"ops-winner\","
                                + "\"reason\":\"confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-caller")
                        .header("Idempotency-Key", "e2e-conflict")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_FAILED\",\"reconciledBy\":\"ops-loser\","
                                + "\"reason\":\"dispute\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILE_CONFLICT"))
                .andExpect(jsonPath("$.effectiveVerdict").value("CONFIRMED_SUCCEEDED"))
                .andExpect(jsonPath("$.reconciledBy").value("ops-winner"));

        System.out.println("[输入] 已回写成功后再回写失败；[判定依据] 结论矛盾；"
                + "[结论] 409 RECONCILE_CONFLICT，响应含胜出方 ops-winner");
    }

    @Test
    @DisplayName("区分拒绝：不存在 404 / 非待对账 409；积压观测端点返回 pending 与上限")
    void distinctRejectionsAndBacklogEndpoint() throws Exception {
        mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-caller")
                        .header("Idempotency-Key", "e2e-missing")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_SUCCEEDED\",\"reconciledBy\":\"ops\","
                                + "\"reason\":\"r\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECONCILE_KEY_NOT_FOUND"));

        // 制造一个 SUCCEEDED 键（非待对账）
        when(client.transfer(any(TransferRequest.class), anyString()))
                .thenReturn(new TransferResult("TX-OK", "COMPLETED", 100));
        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "e2e-caller")
                        .header("Idempotency-Key", "e2e-succ")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-caller")
                        .header("Idempotency-Key", "e2e-succ")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_FAILED\",\"reconciledBy\":\"ops\","
                                + "\"reason\":\"r\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILE_NOT_PENDING"));

        // 积压观测：制造一条 UNCERTAIN 前后 pending 差值为 1
        MvcResult before = mockMvc.perform(get("/commands/reconciliations/backlog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxPending").isNumber())
                .andReturn();
        long pendingBefore = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(before.getResponse().getContentAsString(), "$.pending")
                        .toString());

        submitUncertain("e2e-caller", "e2e-backlog");

        MvcResult after = mockMvc.perform(get("/commands/reconciliations/backlog"))
                .andExpect(status().isOk())
                .andReturn();
        long pendingAfter = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(after.getResponse().getContentAsString(), "$.pending")
                        .toString());

        System.out.println("[观测] 制造 UNCERTAIN 前 pending=" + pendingBefore
                + "，后 pending=" + pendingAfter);
        assertEquals(pendingBefore + 1, pendingAfter, "新增待对账记录必须反映在积压观测中");
    }

    @Test
    @DisplayName("回写依据中的凭据样式内容在响应中被脱敏")
    void reasonIsSanitizedInResponse() throws Exception {
        submitUncertain("e2e-caller", "e2e-sanitize");

        MvcResult rec = mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-caller")
                        .header("Idempotency-Key", "e2e-sanitize")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_SUCCEEDED\",\"reconciledBy\":\"ops\","
                                + "\"reason\":\"verified via token=abc123xyz channel\"}"))
                .andExpect(status().isOk())
                .andReturn();

        System.out.println("[输入] reason 含 token= 样式内容；[判定依据] 响应脱敏；[结论] 不出现凭据原文");
        assertFalse(rec.getResponse().getContentAsString().contains("abc123xyz"),
                "响应中不得出现凭据样式内容");
    }
}
