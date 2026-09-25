package com.github.xingjianluo.resilientidempotentgatewayjava.command;

import com.github.xingjianluo.resilientidempotentgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.AcquireOutcome;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.AcquireResult;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotentRecord;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.RequestStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamErrorType;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamOutcome;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilienceEngine;
import com.github.xingjianluo.resilientidempotentgatewayjava.trace.TraceIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 幂等命令入口服务。
 *
 * <p>处理顺序：参数校验 -> 幂等槽位获取 ->（首次）业务执行 -> 终态落库。
 * 重复请求不执行业务，直接返回与首次一致的稳定结论；不确定结果显式标记而非静默重试。
 * 所有日志先脱敏，所有对外结论只含错误码与可读消息，不含堆栈。
 */
@Service
public class CommandService {

    /** 下游依赖名，对应配置 gateway.resilience.dependencies.payment。 */
    public static final String DEPENDENCY_PAYMENT = "payment";

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final IdempotencyStore store;
    private final ResilienceEngine resilienceEngine;
    private final SimulatedPaymentClient client;
    private final GatewayProperties properties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public CommandService(IdempotencyStore store, ResilienceEngine resilienceEngine,
                          SimulatedPaymentClient client, GatewayProperties properties) {
        this.store = store;
        this.resilienceEngine = resilienceEngine;
        this.client = client;
        this.properties = properties;
    }

    /** HTTP 层调用：携带状态码的完整结果。 */
    public CommandResult execute(String callerId, String idempotencyKey, CommandRequest request,
                                 String incomingTraceId) {
        String traceId = TraceIds.resolve(incomingTraceId);
        MDC.put("traceId", traceId);
        try {
            return doExecute(callerId, idempotencyKey, request, traceId);
        } finally {
            MDC.remove("traceId");
        }
    }

    private CommandResult doExecute(String callerId, String idempotencyKey, CommandRequest request,
                                    String traceId) {
        log.info("command received callerId={} key={} payload={}",
                SensitiveDataSanitizer.mask(callerId),
                SensitiveDataSanitizer.mask(idempotencyKey),
                SensitiveDataSanitizer.mask(safePayloadDescription(request)));

        if (isBlank(callerId) || isBlank(idempotencyKey)) {
            return CommandResult.error(400, "MISSING_IDEMPOTENCY_HEADER",
                    "both X-Caller-Id and Idempotency-Key headers are required", null, traceId);
        }
        if (request == null || isBlank(request.scenario()) || isBlank(request.orderId())) {
            return CommandResult.error(400, "INVALID_REQUEST",
                    "scenario and orderId are required", null, traceId);
        }

        String payloadHash = sha256(canonicalPayload(request));
        long waitMillis = properties.getIdempotency().getAcquireWaitMillis();

        AcquireResult acquired;
        try {
            acquired = store.acquire(callerId, idempotencyKey, payloadHash, waitMillis);
        } catch (RuntimeException ex) {
            // 存储层异常同样按"不可用"降级，绝不放行执行（避免副作用重复）
            log.error("acquire raised storage error, fail-closed: {}", ex.toString());
            return CommandResult.error(503, "STORE_UNAVAILABLE",
                    "idempotency storage is temporarily unavailable, request not executed",
                    null, traceId);
        }

        return switch (acquired.outcome()) {
            case STORE_UNAVAILABLE -> CommandResult.error(503, "STORE_UNAVAILABLE",
                    "idempotency storage is temporarily unavailable, request not executed",
                    null, traceId);
            case PAYLOAD_MISMATCH -> CommandResult.error(409, "PAYLOAD_MISMATCH",
                    "idempotency key was already used with a different payload; "
                            + "rejected without executing the side effect", null, traceId);
            case CONCURRENT -> CommandResult.error(409, "CONCURRENT_SUBMISSION",
                    "an identical request is still being processed; no duplicate execution",
                    null, traceId);
            case EXPIRED_REPLAY -> CommandResult.error(410, "IDEMPOTENCY_EXPIRED",
                    "the idempotency key has expired; replay is rejected",
                    null, traceId);
            case UNCERTAIN_REPLAY -> CommandResult.error(409, "RESULT_UNCERTAIN",
                    "the earlier attempt ended in an uncertain state; do NOT retry blindly, "
                            + "reconcile with the downstream before resubmitting",
                    null, traceId);
            case REPLAY -> replayResponse(acquired.record(), traceId);
            case ACCEPTED -> firstExecution(acquired.record(), request, traceId);
        };
    }

    private CommandResult firstExecution(IdempotentRecord record, CommandRequest request, String traceId) {
        DownstreamOutcome<Map<String, Object>> outcome;
        try {
            outcome = resilienceEngine.execute(DEPENDENCY_PAYMENT, client.call(request));
        } catch (RuntimeException unexpected) {
            // 任何未受控异常：结果未知，标记 UNCERTAIN 而非静默重试，堆栈不外泄
            log.error("business execution raised unexpected error: {}", unexpected.toString());
            store.complete(record, RequestStatus.UNCERTAIN, null, "INTERNAL_UNCERTAIN");
            return CommandResult.error(500, "INTERNAL_UNCERTAIN",
                    "the request outcome is uncertain due to an internal error; "
                            + "it has been marked for reconciliation", null, traceId);
        }

        if (outcome.success()) {
            String dataJson = writeJson(outcome.value());
            store.complete(record, RequestStatus.SUCCESS, dataJson, null);
            log.info("first execution SUCCESS key={} attempts={}", record.getIdempotencyKey(),
                    outcome.attempts());
            return CommandResult.ok(200, "OK", "processed", outcome.value(), false, traceId);
        }

        if (outcome.outcomeKnown()) {
            DownstreamErrorType type = outcome.errorType();
            int httpStatus = type == DownstreamErrorType.BUSINESS_REJECTED ? 422 : 502;
            String code = mapErrorCode(type);
            store.complete(record, RequestStatus.FAILED, null, code);
            log.warn("first execution FAILED (known) key={} type={} attempts={}",
                    record.getIdempotencyKey(), type, outcome.attempts());
            return CommandResult.error(httpStatus, code,
                    safeMessage(outcome.errorMessage()), null, traceId);
        }

        // 结果未知（超时/中断等）：显式标记不确定，部分成功绝不静默重试
        String code = mapErrorCode(outcome.errorType());
        store.complete(record, RequestStatus.UNCERTAIN, null, code);
        log.warn("first execution UNCERTAIN key={} type={} attempts={} circuit={}",
                record.getIdempotencyKey(), outcome.errorType(), outcome.attempts(),
                outcome.circuitState());
        return CommandResult.error(504, code,
                "the downstream outcome is uncertain (e.g. timed out after possible side effect); "
                        + "request marked UNCERTAIN, do not retry blindly", null, traceId);
    }

    private CommandResult replayResponse(IdempotentRecord record, String traceId) {
        String key = record.getIdempotencyKey();
        switch (record.getStatus()) {
            case SUCCESS -> {
                log.info("replay returns the original SUCCESS result key={}", key);
                Map<String, Object> data = parseData(record.getStoredResultJson());
                return CommandResult.ok(200, "OK", "replayed original result", data, true, traceId);
            }
            case FAILED -> {
                log.info("replay returns the original FAILED result key={} code={}",
                        key, record.getErrorCode());
                String code = record.getErrorCode() != null ? record.getErrorCode() : "DOWNSTREAM_FAILED";
                int httpStatus = "BUSINESS_REJECTED".equals(code) ? 422 : 502;
                return CommandResult.error(httpStatus, code,
                        "replayed original failure; side effect was not duplicated", null, true, traceId);
            }
            case UNCERTAIN -> {
                log.warn("replay of UNCERTAIN record key={}", key);
                return CommandResult.error(409, "RESULT_UNCERTAIN",
                        "the earlier attempt ended in an uncertain state; reconcile before resubmitting",
                        true, traceId);
            }
            default -> {
                return CommandResult.error(409, "CONCURRENT_SUBMISSION",
                        "request is still being processed", true, traceId);
            }
        }
    }

    /** 把落库的 JSON 文本还原为 Map；失败时以文本字段承载，保证回放结论稳定。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseData(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                return objectMapper.readValue(trimmed, Map.class);
            }
        } catch (Exception ignore) {
            // 降级为文本承载
        }
        return Map.of("stored", json);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("failed to serialize result data, fallback to string: {}", ex.toString());
            return String.valueOf(value);
        }
    }

    private String mapErrorCode(DownstreamErrorType type) {
        if (type == null) {
            return "DOWNSTREAM_FAILED";
        }
        return switch (type) {
            case TIMEOUT -> "DOWNSTREAM_TIMEOUT";
            case BUSINESS_REJECTED -> "BUSINESS_REJECTED";
            case RETRY_EXHAUSTED -> "DOWNSTREAM_RETRY_EXHAUSTED";
            case CIRCUIT_OPEN -> "DOWNSTREAM_CIRCUIT_OPEN";
            case BULKHEAD_FULL -> "DOWNSTREAM_BULKHEAD_FULL";
            case DOWNSTREAM_ERROR -> "DOWNSTREAM_ERROR";
            default -> "DOWNSTREAM_UNKNOWN";
        };
    }

    private String safeMessage(String message) {
        String text = SensitiveDataSanitizer.mask(message);
        if (text == null || text.isBlank() || text.equals("null")) {
            return "downstream request failed";
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    /** 参与哈希的规范化载荷：只取业务字段，绝不含 credential。 */
    private String canonicalPayload(CommandRequest request) {
        return "scenario=" + nullSafe(request.scenario())
                + "|orderId=" + nullSafe(request.orderId())
                + "|amount=" + (request.amount() == null ? "" : request.amount());
    }

    private String safePayloadDescription(CommandRequest request) {
        if (request == null) {
            return "null";
        }
        // 显式构造描述，避免 credential 出现在日志里
        return "scenario=" + nullSafe(request.scenario())
                + ",orderId=" + nullSafe(request.orderId())
                + ",amount=" + (request.amount() == null ? "" : request.amount())
                + ",credential=" + SensitiveDataSanitizer.redact();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
