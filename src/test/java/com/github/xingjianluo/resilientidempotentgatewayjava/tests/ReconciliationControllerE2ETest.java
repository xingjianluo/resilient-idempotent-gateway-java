package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRecord;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 对账回写端到端测试：HTTP 入口 -> 回写收敛 -> 重复提交返回稳定终态；
 * 区分拒绝（不存在/非待对账/冲突）；积压观测；响应不含凭据。
 * <p>
 * UNCERTAIN 记录通过存储直接播种（与下游超时产生的记录结构一致），
 * 避免在共享上下文中反复触发下游超时把熔断器打打开、干扰其他 E2E 用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationControllerE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdempotencyStore store;

    @Autowired
    private IdempotencyProperties properties;

    private static final String BODY =
            "{\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amountCents\":100,\"credential\":\"super-secret-token\"}";

    @BeforeEach
    void resetStore() {
        store.setAvailable(true);
    }

    /** 直接播种一条“结果未知、尚未对账”的记录（等价于下游超时后的落库状态）。 */
    private void seedUncertain(String caller, String key, String rawBody) {
        IdempotencyRecord record = IdempotencyRecord.inFlight(caller, key, sha256(rawBody), Instant.now());
        store.claim(record, properties.getTtl());
        record.setStatus(IdempotencyStatus.UNCERTAIN);
        record.setErrorCode("DOWNSTREAM_TIMEOUT");
        record.setCompletedAt(Instant.now());
        store.complete(record, properties.getTtl());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("E2E：未知态 -> 对账回写确认成功 -> 重复提交返回稳定 SUCCEEDED 且 replayed=true")
    void uncertainThenReconcileThenStableReplay() throws Exception {
        seedUncertain("e2e-c", "e2e-rec-1", BODY);

        // 回写前：重复提交仍重放 UNCERTAIN(202)
        mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "e2e-c")
                        .header("Idempotency-Key", "e2e-rec-1")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.idempotencyStatus").value("UNCERTAIN"))
                .andExpect(jsonPath("$.replayed").value(true));

        MvcResult rec = mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-c")
                        .header("Idempotency-Key", "e2e-rec-1")
                        .header("X-Trace-Id", "trace-reconcile-1")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_SUCCEEDED\",\"reconciledBy\":\"ops-li\","
                                + "\"basis\":\"ledger-statement-20261007\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trace-reconcile-1"))
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.idempotencyStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.reconciliation.reconciledBy").value("ops-li"))
                .andExpect(jsonPath("$.reconciliation.reconciledTraceId").value("trace-reconcile-1"))
                .andReturn();

        MvcResult replay = mockMvc.perform(post("/commands/transfers")
                        .header("X-Caller-Id", "e2e-c")
                        .header("Idempotency-Key", "e2e-rec-1")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn();

        System.out.println("[输入] UNCERTAIN 记录 -> 回写 CONFIRMED_SUCCEEDED -> 同键重复提交；"
                + "[判定依据] 回写后记录为稳定终态；[状态迁移] UNCERTAIN->SUCCEEDED，重复提交 REPLAYED");
        assertFalse(rec.getResponse().getContentAsString().contains("super-secret-token"),
                "回写响应不得出现凭据");
        assertFalse(replay.getResponse().getContentAsString().contains("super-secret-token"));
    }

    @Test
    @DisplayName("E2E：区分拒绝——不存在 404 KEY_NOT_FOUND；非待对账 409 NOT_PENDING；冲突 409 VERDICT_CONFLICT")
    void distinctRejectionsOverHttp() throws Exception {
        // 不存在的键
        mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-c").header("Idempotency-Key", "e2e-absent")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_SUCCEEDED\",\"reconciledBy\":\"ops\",\"basis\":\"b\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_KEY_NOT_FOUND"));

        // 原生 SUCCEEDED 的键（直接播种终态，不经过下游）
        IdempotencyRecord nativeOk = IdempotencyRecord.inFlight("e2e-c", "e2e-native-ok",
                sha256(BODY), Instant.now());
        store.claim(nativeOk, properties.getTtl());
        nativeOk.setStatus(IdempotencyStatus.SUCCEEDED);
        nativeOk.setCompletedAt(Instant.now());
        store.complete(nativeOk, properties.getTtl());

        mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-c").header("Idempotency-Key", "e2e-native-ok")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_FAILED\",\"reconciledBy\":\"ops\",\"basis\":\"b\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_NOT_PENDING"));

        // 冲突：先回写成功，再回写失败
        seedUncertain("e2e-c", "e2e-conflict", BODY);
        mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-c").header("Idempotency-Key", "e2e-conflict")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_SUCCEEDED\",\"reconciledBy\":\"ops-first\",\"basis\":\"b1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));
        MvcResult conflict = mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-c").header("Idempotency-Key", "e2e-conflict")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_FAILED\",\"reconciledBy\":\"ops-second\",\"basis\":\"b2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_VERDICT_CONFLICT"))
                .andReturn();
        System.out.println("[输入] 不存在键/原生成功键/已回写成功键分别回写；[判定依据] 存在性、状态机、既有回写结论；"
                + "[结论] 404 KEY_NOT_FOUND / 409 NOT_PENDING / 409 VERDICT_CONFLICT");
        assertTrue(conflict.getResponse().getContentAsString().contains("ops-first"),
                "冲突响应应指明被谁抢先");
    }

    @Test
    @DisplayName("E2E：积压观测端点反映待对账量，回写后下降")
    void backlogEndpointReflectsPending() throws Exception {
        long before = store.pendingReconciliationCount();
        seedUncertain("e2e-c", "e2e-backlog-1", BODY);

        mockMvc.perform(get("/commands/reconciliation-backlog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingReconciliation").value(before + 1))
                .andExpect(jsonPath("$.maxPendingReconciliation").isNumber())
                .andExpect(jsonPath("$.utilization").isNumber());

        mockMvc.perform(post("/commands/reconciliations")
                        .header("X-Caller-Id", "e2e-c").header("Idempotency-Key", "e2e-backlog-1")
                        .contentType("application/json")
                        .content("{\"verdict\":\"CONFIRMED_FAILED\",\"reconciledBy\":\"ops\",\"basis\":\"b\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/commands/reconciliation-backlog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingReconciliation").value(before));
        System.out.println("[输入] 产生一条 UNCERTAIN 后观测积压再回写；[判定依据] 观测口径=UNCERTAIN 未回写；"
                + "[状态] 积压 +1，回写后回落");
    }
}
