package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

import java.time.Instant;

/**
 * 一次对账回写的权威结论与审计信息：谁、什么时间、依据什么、
 * 在哪个追踪标识下，把 UNCERTAIN 改成了哪个终态。
 * <p>
 * 改动前遗留的 UNCERTAIN 记录该字段为 null，视为“尚未对账”，可正常回写。
 */
public final class ReconciliationInfo {

    private final IdempotencyStatus verdictStatus;
    private final String reconciledBy;
    private final Instant reconciledAt;
    private final String basis;
    private final String traceId;

    public ReconciliationInfo(IdempotencyStatus verdictStatus, String reconciledBy,
                              Instant reconciledAt, String basis, String traceId) {
        this.verdictStatus = verdictStatus;
        this.reconciledBy = reconciledBy;
        this.reconciledAt = reconciledAt;
        this.basis = basis;
        this.traceId = traceId;
    }

    /** 回写后的稳定终态（SUCCEEDED / FAILED）。 */
    public IdempotencyStatus getVerdictStatus() {
        return verdictStatus;
    }

    public String getReconciledBy() {
        return reconciledBy;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }

    public String getBasis() {
        return basis;
    }

    public String getTraceId() {
        return traceId;
    }
}
