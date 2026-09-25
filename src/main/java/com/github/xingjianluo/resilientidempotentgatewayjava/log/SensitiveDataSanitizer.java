package com.github.xingjianluo.resilientidempotentgatewayjava.log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏工具。
 * <p>
 * 日志与任何对外结论中都不得出现凭据原文；对 JSON 文本中的
 * password/passwd/token/secret/credential/authorization/apiKey 等字段
 * （大小写不敏感）统一替换为固定占位符，同时也兼容 form 表单形式。
 */
public final class SensitiveDataSanitizer {

    /** 命中敏感键时，其字符串值被替换为该占位符。 */
    public static final String MASKED = "***";

    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:password|passwd|token|secret|credential|credentials|authorization|apikey|api-key)\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final Pattern FORM_SECRET = Pattern.compile(
            "(?i)((?:password|passwd|token|secret|credential|credentials|authorization|apikey|api-key)=)[^&\\s]+");

    /** 出现在任意文本中的 Bearer/ApiKey 头样式凭据。 */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+");

    private SensitiveDataSanitizer() {
    }

    /** 对 JSON / 表单 / Bearer 文本中的敏感字段脱敏。 */
    public static String sanitizeJson(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = JSON_SECRET.matcher(text).replaceAll("$1\"" + MASKED + "\"");
        out = FORM_SECRET.matcher(out).replaceAll("$1" + MASKED);
        out = BEARER.matcher(out).replaceAll("$1" + MASKED);
        return out;
    }

    /** 通用遮蔽：null 原样返回；长度不超过 4 全部遮蔽；否则保留首尾各 2 个字符。 */
    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        int n = value.length();
        if (n == 0) {
            return "";
        }
        if (n <= 4) {
            return MASKED;
        }
        return value.substring(0, 2) + MASKED + value.substring(n - 2);
    }
}
