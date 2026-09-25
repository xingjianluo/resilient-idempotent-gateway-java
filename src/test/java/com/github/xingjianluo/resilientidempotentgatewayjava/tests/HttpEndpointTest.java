package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.tests.support.TestLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP 端到端：真实 Spring 上下文 + MockMvc，仍完全本地、无外部服务。 */
@SpringBootTest(properties = {
        "gateway.idempotency.ttl-seconds=60",
        "gateway.idempotency.acquire-wait-millis=3000",
        "gateway.resilience.dependencies.payment.call-timeout-millis=200",
        "gateway.resilience.dependencies.payment.max-attempts=1",
        "gateway.resilience.dependencies.payment.failure-threshold=5",
        "gateway.resilience.dependencies.payment.open-millis=10000",
        "gateway.resilience.dependencies.payment.bulkhead-max-concurrent=4"
})
@AutoConfigureMockMvc
class HttpEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdempotencyStore store;

    private final String successBody = """
            {"scenario":"success","orderId":"HTTP-1","amount":50,"credential":"topsecret-token"}
            """;

    @Test
    void firstThenDuplicateOverHttp() throws Exception {
        TestLog.section("HTTP：首次 200，重复 200 且 replay=true，错误体不含堆栈/凭据");
        MvcResult first = mockMvc.perform(post("/api/commands")
                        .header("X-Caller-Id", "svc-a")
                        .header("Idempotency-Key", "http-key-1")
                        .header("X-Trace-Id", "tr-caller-supplied-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.traceId").value("tr-caller-supplied-0001"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        TestLog.basis("首次成功，traceId 复用调用方传入值: tr-caller-supplied-0001");

        mockMvc.perform(post("/api/commands")
                        .header("X-Caller-Id", "svc-a")
                        .header("Idempotency-Key", "http-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.charged").value(true));
        TestLog.transition("重复请求返回 replay=true 的稳定结果");

        String responseText = first.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(responseText.contains("topsecret-token"),
                "响应中不得出现凭据");
        org.junit.jupiter.api.Assertions.assertFalse(responseText.contains("Exception"),
                "响应中不得出现异常类型/堆栈");
    }

    @Test
    void missingHeadersAndBadBodyAreRejectedWithStableCodes() throws Exception {
        TestLog.section("HTTP：缺少幂等头 / 载荷错误 -> 稳定 4xx");
        mockMvc.perform(post("/api/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_HEADER"));

        mockMvc.perform(post("/api/commands")
                        .header("X-Caller-Id", "svc-a")
                        .header("Idempotency-Key", "http-key-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void timeoutReturns504WithoutStack() throws Exception {
        TestLog.section("HTTP：下游超时 -> 504 DOWNSTREAM_TIMEOUT，无堆栈透出");
        String body = """
                {"scenario":"timeout","orderId":"HTTP-T","amount":1,"credential":"x"}
                """;
        mockMvc.perform(post("/api/commands")
                        .header("X-Caller-Id", "svc-a")
                        .header("Idempotency-Key", "http-key-timeout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Exception"))));
    }

    @Test
    void storeOutageDegradesTo503() throws Exception {
        TestLog.section("HTTP：存储不可用 -> 503 STORE_UNAVAILABLE（fail-closed）");
        store.setAvailable(false);
        try {
            mockMvc.perform(post("/api/commands")
                            .header("X-Caller-Id", "svc-a")
                            .header("Idempotency-Key", "http-key-outage")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(successBody))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STORE_UNAVAILABLE"));
        } finally {
            store.setAvailable(true);
            TestLog.transition("存储恢复后同键应能正常首次执行");
            mockMvc.perform(post("/api/commands")
                            .header("X-Caller-Id", "svc-a")
                            .header("Idempotency-Key", "http-key-outage")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(successBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"));
        }
    }
}
