package com.github.xingjianluo.resilientidempotentgatewayjava.log;

import java.util.regex.Pattern;

/**
 * 敏感数据脱敏：任何进入日志或对外结论的文本都先经过本类处理，
 * 确保凭据（credential/password/token/secret/authorization/api-key）不泄漏。
 *
 * <p>同时剥离 CR/LF，防止日志伪造。
 */
public final class SensitiveDataSanitizer {

    private static final String REDACTED = "***REDACTED***";

    /** JSON 形态："credential":"abc123" */
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:password|passwd|credential|token|secret|authorization|auth|apikey|api[-_]?key)"
                    + "\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");

    /** key=value 形态：credential=abc123 */
    private static final Pattern KV_SECRET = Pattern.compile(
            "(?i)(\\b(?:password|passwd|credential|token|secret|authorization|auth|api[-_]?key)\\s*[=:]\\s*)"
                    + "[^\\s,;}\\]]+");

    /** Authorization 头形态：Bearer abc123 */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*");

    /** 整个值都需要被视为凭据（例如单独打印的 credential 字段）。 */
    public static String redact() {
        return REDACTED;
    }

    public static String mask(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        text = JSON_SECRET.matcher(text).replaceAll("$1" + REDACTED + "$2");
        text = KV_SECRET.matcher(text).replaceAll("$1" + REDACTED);
        text = BEARER.matcher(text).replaceAll("$1" + REDACTED);
        // 防止日志换行注入
        text = text.replace('\r', '_').replace('\n', '_');
        if (text.length() > 1000) {
            text = text.substring(0, 1000) + "...(truncated)";
        }
        return text;
    }
}
