package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

import java.time.Instant;

/**
 * 幂等存储中的一条记录。状态迁移仅允许：
 * IN_FLIGHT -> SUCCEEDED / FAILED / UNCERTAIN。
 */
public final class IdempotencyRecord {

    private final String callerId;
    private final String key;
    private final String payloadHash;
    private volatile IdempotencyStatus status;
    private volatile String resultJson;
    private volatile String errorCode;
    private final Instant createdAt;
    private volatile Instant completedAt;
    /**
     * 对账回写信息；null 表示尚未对账。
     * 改动前遗留的 UNCERTAIN 记录该字段天然为 null，兼容无需迁移。
     */
    private volatile ReconciliationInfo reconciliation;

    public IdempotencyRecord(String callerId, String key, String payloadHash,
                             IdempotencyStatus status, Instant createdAt) {
        this.callerId = callerId;
        this.key = key;
        this.payloadHash = payloadHash;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** 首个请求的占位记录（IN_FLIGHT）。 */
    public static IdempotencyRecord inFlight(String callerId, String key, String payloadHash, Instant now) {
        return new IdempotencyRecord(callerId, key, payloadHash, IdempotencyStatus.IN_FLIGHT, now);
    }

    public String getCallerId() {
        return callerId;
    }

    public String getKey() {
        return key;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public void setStatus(IdempotencyStatus status) {
        this.status = status;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public ReconciliationInfo getReconciliation() {
        return reconciliation;
    }

    public void setReconciliation(ReconciliationInfo reconciliation) {
        this.reconciliation = reconciliation;
    }
}
