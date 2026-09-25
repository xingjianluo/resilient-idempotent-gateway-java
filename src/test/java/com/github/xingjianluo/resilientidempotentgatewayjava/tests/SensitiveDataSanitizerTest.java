package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 敏感信息脱敏测试：保障日志/结论中不出现凭据原文。
 */
class SensitiveDataSanitizerTest {

    @Test
    @DisplayName("JSON 中的 password/token/credential 等字段被脱敏")
    void jsonSecretsMasked() {
        String json = "{\"fromAccount\":\"A\",\"credential\":\"super-secret-token\","
                + "\"nested\":{\"password\":\"p@ss\"},\"authorization\":\"Bearer abc123\"}";
        String out = SensitiveDataSanitizer.sanitizeJson(json);
        System.out.println("[输入] " + json + "  =>  [输出] " + out);
        assertFalse(out.contains("super-secret-token"));
        assertFalse(out.contains("p@ss"));
        assertFalse(out.contains("abc123"));
        assertTrue(out.contains("***"));
        // 非敏感字段保留
        assertTrue(out.contains("\"fromAccount\":\"A\""));
    }

    @Test
    @DisplayName("表单与 Bearer 头样式凭据被脱敏")
    void formAndBearerMasked() {
        String form = "user=alice&token=tok_abcdef&amount=100";
        String out = SensitiveDataSanitizer.sanitizeJson(form);
        assertFalse(out.contains("tok_abcdef"));
        assertTrue(out.contains("token=***"));

        String bearer = SensitiveDataSanitizer.sanitizeJson("Authorization: Bearer zzzzzzzzzzzz");
        assertFalse(bearer.contains("zzzzzzzzzzzz"));
        assertTrue(bearer.contains("Bearer ***"));
    }

    @Test
    @DisplayName("mask 保留前后少量字符，不泄漏整体")
    void maskKeepsEdgesOnly() {
        assertEquals("ab***yz", SensitiveDataSanitizer.mask("abcdefghijklmyz"));
        assertEquals("***", SensitiveDataSanitizer.mask("abc"));
        assertNull(SensitiveDataSanitizer.mask(null));
    }
}
