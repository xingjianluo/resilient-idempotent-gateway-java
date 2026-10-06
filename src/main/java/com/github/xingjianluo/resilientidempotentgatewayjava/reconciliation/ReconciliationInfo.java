package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import java.time.Instant;

/**
 * 一次对账回写的审计元数据：谁、什么时间、依据什么、把未知改成了什么结论，
 * 以及发起回写时的追踪标识。回写生效后随记录一起保存，供后续重放与排查串联。
 * <p>
 * 兼容说明：本次改动之前产生的遗留 UNCERTAIN 记录没有该字段（为 null），
 * 视为“尚未对账”，可被正常回写。
 */
public final class ReconciliationInfo {

    private final String reconciledBy;
    private final Instant reconciledAt;
    private final String reason;
    private final String traceId;
    private final ReconciliationVerdict verdict;

    public ReconciliationInfo(String reconciledBy, Instant reconciledAt, String reason,
                              String traceId, ReconciliationVerdict verdict) {
        this.reconciledBy = reconciledBy;
        this.reconciledAt = reconciledAt;
        this.reason = reason;
        this.traceId = traceId;
        this.verdict = verdict;
    }

    /** 执行对账回写的操作者标识。 */
    public String getReconciledBy() {
        return reconciledBy;
    }

    /** 回写生效时间。 */
    public Instant getReconciledAt() {
        return reconciledAt;
    }

    /** 回写依据（对账结论来源说明）。 */
    public String getReason() {
        return reason;
    }

    /** 发起回写请求的追踪标识。 */
    public String getTraceId() {
        return traceId;
    }

    /** 回写结论。 */
    public ReconciliationVerdict getVerdict() {
        return verdict;
    }
}
