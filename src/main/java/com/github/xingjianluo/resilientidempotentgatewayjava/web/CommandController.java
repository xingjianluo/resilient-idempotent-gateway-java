package com.github.xingjianluo.resilientidempotentgatewayjava.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.resilientidempotentgatewayjava.demo.TransferRequest;
import com.github.xingjianluo.resilientidempotentgatewayjava.demo.TransferResult;
import com.github.xingjianluo.resilientidempotentgatewayjava.demo.TransferService;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyOutcome;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotentCommandService;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
import com.github.xingjianluo.resilientidempotentgatewayjava.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 幂等命令 HTTP 入口。
 * <p>
 * 必需头：{@code X-Caller-Id}、{@code Idempotency-Key}；
 * 可选头：{@code X-Trace-Id}（缺省自动生成）。
 * <p>
 * 对载荷做规范化 SHA-256 摘要后交由幂等服务收敛；响应信封包含
 * traceId、幂等状态、是否重放、错误码与结果。输入日志经脱敏处理。
 */
@RestController
@RequestMapping("/commands")
public class CommandController {

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);

    private final IdempotentCommandService idempotentService;
    private final TransferService transferService;
    private final ObjectMapper objectMapper;

    public CommandController(IdempotentCommandService idempotentService,
                             TransferService transferService, ObjectMapper objectMapper) {
        this.idempotentService = idempotentService;
        this.transferService = transferService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/transfers")
    public ResponseEntity<Map<String, Object>> transfer(
            @RequestHeader("X-Caller-Id") String callerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String inboundTraceId,
            @RequestBody String rawBody) {

        String traceId = TraceContext.begin(inboundTraceId);
        try {
            // 先以原始载荷计算摘要（包含全部字段，凭据变化也会导致载荷不同）
            String payloadHash = sha256(rawBody);

            TransferRequest request = parse(rawBody);
            log.info("[{}] command received callerId={} key={} payloadHash={} body={}",
                    traceId, callerId, idempotencyKey,
                    SensitiveDataSanitizer.mask(payloadHash),
                    SensitiveDataSanitizer.sanitizeJson(rawBody));

            IdempotencyOutcome<TransferResult> outcome = idempotentService.execute(
                    callerId, idempotencyKey, payloadHash, traceId, TransferResult.class,
                    () -> transferService.execute(request, traceId));

            return toResponse(outcome, traceId);
        } finally {
            TraceContext.end();
        }
    }

    private TransferRequest parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, TransferRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("request body must be valid JSON: " + e.getOriginalMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> toResponse(IdempotencyOutcome<TransferResult> outcome,
                                                           String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("idempotencyStatus", outcome.getStatus().name());
        body.put("replayed", outcome.getKind() == IdempotencyOutcome.Kind.REPLAYED);
        body.put("errorCode", outcome.getErrorCode());
        body.put("result", outcome.getResult());

        HttpStatus httpStatus = switch (outcome.getStatus()) {
            case SUCCEEDED -> HttpStatus.OK;
            case FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNCERTAIN -> HttpStatus.ACCEPTED; // 202：请求已受理，最终结果未知，需对账
            case IN_FLIGHT -> HttpStatus.ACCEPTED;
        };
        log.info("[{}] command response httpStatus={} status={} replayed={} errorCode={}",
                traceId, httpStatus, outcome.getStatus(),
                outcome.getKind() == IdempotencyOutcome.Kind.REPLAYED, outcome.getErrorCode());
        return ResponseEntity.status(httpStatus).body(body);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
