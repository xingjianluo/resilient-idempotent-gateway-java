package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStatus;

/**
 * 对账回写入口的执行结论。
 */
public final class ReconciliationOutcome {

    public enum Kind {
        /** 本次回写真正生效，记录迁移到稳定终态 */
        APPLIED,
        /** 相同结论此前已生效（并发/重复回写），本次幂等返回已生效结论 */
        ALREADY_RECONCILED
    }

    private final Kind kind;
    private final IdempotencyStatus status;
    private final ReconciliationInfo effectiveInfo;
    private final String traceId;

    private ReconciliationOutcome(Kind kind, IdempotencyStatus status,
                                  ReconciliationInfo effectiveInfo, String traceId) {
        this.kind = kind;
        this.status = status;
        this.effectiveInfo = effectiveInfo;
        this.traceId = traceId;
    }

    public static ReconciliationOutcome applied(ReconciliationInfo info, String traceId) {
        return new ReconciliationOutcome(Kind.APPLIED, info.getVerdict().targetStatus(), info, traceId);
    }

    public static ReconciliationOutcome alreadyReconciled(ReconciliationInfo effectiveInfo, String traceId) {
        return new ReconciliationOutcome(Kind.ALREADY_RECONCILED,
                effectiveInfo.getVerdict().targetStatus(), effectiveInfo, traceId);
    }

    public Kind getKind() {
        return kind;
    }

    /** 回写后记录的稳定终态。 */
    public IdempotencyStatus getStatus() {
        return status;
    }

    /** 实际生效的回写元数据（并发落败时即胜出方的信息）。 */
    public ReconciliationInfo getEffectiveInfo() {
        return effectiveInfo;
    }

    public String getTraceId() {
        return traceId;
    }
}
