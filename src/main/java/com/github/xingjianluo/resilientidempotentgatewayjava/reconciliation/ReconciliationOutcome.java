package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.ReconciliationInfo;

/**
 * 对账回写结果。{@code applied=true} 表示本次回写真正生效；
 * {@code applied=false} 表示该键已被他人以相同结论回写，本次拿到已生效结论。
 */
public final class ReconciliationOutcome {

    private final boolean applied;
    private final IdempotencyStatus status;
    private final ReconciliationInfo reconciliation;
    private final String traceId;

    private ReconciliationOutcome(boolean applied, IdempotencyStatus status,
                                  ReconciliationInfo reconciliation, String traceId) {
        this.applied = applied;
        this.status = status;
        this.reconciliation = reconciliation;
        this.traceId = traceId;
    }

    public static ReconciliationOutcome applied(ReconciliationInfo info, String traceId) {
        return new ReconciliationOutcome(true, info.getVerdictStatus(), info, traceId);
    }

    public static ReconciliationOutcome alreadyApplied(ReconciliationInfo info, String traceId) {
        return new ReconciliationOutcome(false, info.getVerdictStatus(), info, traceId);
    }

    public boolean isApplied() {
        return applied;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public ReconciliationInfo getReconciliation() {
        return reconciliation;
    }

    public String getTraceId() {
        return traceId;
    }
}
