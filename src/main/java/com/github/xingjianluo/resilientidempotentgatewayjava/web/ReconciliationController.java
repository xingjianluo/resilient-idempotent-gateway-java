package com.github.xingjianluo.resilientidempotentgatewayjava.web;

import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationInfo;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationOutcome;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationService;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationVerdict;
import com.github.xingjianluo.resilientidempotentgatewayjava.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对账回写 HTTP 入口。
 * <p>
 * {@code POST /commands/reconciliations}：对处于“结果未知、尚未对账”的键补写权威结论；
 * {@code GET /commands/reconciliations/backlog}：观测当前待对账积压量与上限。
 */
@RestController
@RequestMapping("/commands/reconciliations")
public class ReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationController.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** 对账回写请求体。reason 为对账依据说明，日志与响应中均脱敏。 */
    public record ReconcileRequest(ReconciliationVerdict verdict, String reconciledBy, String reason) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> reconcile(
            @RequestHeader("X-Caller-Id") String callerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReconcileRequest request) {

        String traceId = TraceContext.currentTraceId();
        log.info("[{}] reconciliation requested callerId={} key={} verdict={} by={} reason={}",
                traceId, callerId, idempotencyKey, request.verdict(), request.reconciledBy(),
                SensitiveDataSanitizer.sanitizeJson(request.reason()));

        ReconciliationOutcome outcome = reconciliationService.reconcile(
                callerId, idempotencyKey, request.verdict(),
                request.reconciledBy(), request.reason(), traceId);

        ReconciliationInfo effective = outcome.getEffectiveInfo();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("idempotencyStatus", outcome.getStatus().name());
        body.put("applied", outcome.getKind() == ReconciliationOutcome.Kind.APPLIED);
        body.put("reconciledBy", effective.getReconciledBy());
        body.put("reconciledAt", effective.getReconciledAt().toString());
        body.put("reconciliationTraceId", effective.getTraceId());
        body.put("reason", SensitiveDataSanitizer.sanitizeJson(effective.getReason()));

        log.info("[{}] reconciliation response status={} applied={} effectiveBy={}",
                traceId, outcome.getStatus(), outcome.getKind() == ReconciliationOutcome.Kind.APPLIED,
                effective.getReconciledBy());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/backlog")
    public ResponseEntity<Map<String, Object>> backlog() {
        String traceId = TraceContext.currentTraceId();
        ReconciliationService.BacklogSnapshot snapshot = reconciliationService.backlogSnapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("pending", snapshot.pending());
        body.put("maxPending", snapshot.maxPending());
        body.put("includeExpired", snapshot.includeExpired());
        log.info("[{}] backlog snapshot pending={} maxPending={} includeExpired={}",
                traceId, snapshot.pending(), snapshot.maxPending(), snapshot.includeExpired());
        return ResponseEntity.ok(body);
    }
}
