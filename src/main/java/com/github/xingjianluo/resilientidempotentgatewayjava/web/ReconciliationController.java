package com.github.xingjianluo.resilientidempotentgatewayjava.web;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.ReconciliationInfo;
import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
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
 * {@code POST /commands/reconciliations}：对“结果未知、尚未对账”的键回写权威结论；
 * {@code GET /commands/reconciliation-backlog}：待对账积压量观测。
 * <p>
 * 请求体不含凭据；日志对 basis 等自由文本做脱敏。
 */
@RestController
@RequestMapping("/commands")
public class ReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationController.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** 回写请求体：权威结论 + 回写人 + 依据。不含凭据类字段。 */
    public record ReconcileRequest(String verdict, String reconciledBy, String basis) {
    }

    @PostMapping("/reconciliations")
    public ResponseEntity<Map<String, Object>> reconcile(
            @RequestHeader("X-Caller-Id") String callerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String inboundTraceId,
            @RequestBody ReconcileRequest request) {

        String traceId = TraceContext.begin(inboundTraceId);
        try {
            ReconciliationVerdict verdict = parseVerdict(request.verdict());
            log.info("[{}] reconciliation request received callerId={} key={} verdict={} by={} basis={}",
                    traceId, callerId, idempotencyKey, verdict, request.reconciledBy(),
                    SensitiveDataSanitizer.sanitizeJson(request.basis()));

            ReconciliationOutcome outcome = reconciliationService.reconcile(
                    callerId, idempotencyKey, verdict, request.reconciledBy(), request.basis(), traceId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("traceId", traceId);
            body.put("idempotencyKey", idempotencyKey);
            body.put("applied", outcome.isApplied());
            body.put("idempotencyStatus", outcome.getStatus().name());
            ReconciliationInfo info = outcome.getReconciliation();
            Map<String, Object> reconciliation = new LinkedHashMap<>();
            reconciliation.put("reconciledBy", info.getReconciledBy());
            reconciliation.put("reconciledAt", info.getReconciledAt().toString());
            reconciliation.put("basis", info.getBasis());
            reconciliation.put("reconciledTraceId", info.getTraceId());
            body.put("reconciliation", reconciliation);

            log.info("[{}] reconciliation response applied={} status={} key={}",
                    traceId, outcome.isApplied(), outcome.getStatus(), idempotencyKey);
            return ResponseEntity.ok(body);
        } finally {
            TraceContext.end();
        }
    }

    /** 待对账积压观测：当前积压量、上限与利用率。 */
    @GetMapping("/reconciliation-backlog")
    public ResponseEntity<Map<String, Object>> backlog() {
        long pending = reconciliationService.pendingBacklog();
        long max = reconciliationService.maxPendingBacklog();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pendingReconciliation", pending);
        body.put("maxPendingReconciliation", max);
        body.put("utilization", max > 0 ? (double) pending / max : 0.0);
        return ResponseEntity.ok(body);
    }

    private ReconciliationVerdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("verdict is required (CONFIRMED_SUCCEEDED / CONFIRMED_FAILED)");
        }
        try {
            return ReconciliationVerdict.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "verdict must be CONFIRMED_SUCCEEDED or CONFIRMED_FAILED, got: " + raw);
        }
    }
}
