package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStatus;

/**
 * 对账回写的权威结论：把 UNCERTAIN（待对账）记录确认为成功或确认为未成功。
 */
public enum ReconciliationVerdict {
    /** 对账确认：下游实际已成功，记录迁移为 SUCCEEDED 稳定终态 */
    CONFIRMED_SUCCEEDED(IdempotencyStatus.SUCCEEDED),
    /** 对账确认：下游实际未成功（无副作用或已冲正），记录迁移为 FAILED 稳定终态 */
    CONFIRMED_FAILED(IdempotencyStatus.FAILED);

    private final IdempotencyStatus targetStatus;

    ReconciliationVerdict(IdempotencyStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    /** 回写生效后记录应迁移到的稳定终态。 */
    public IdempotencyStatus targetStatus() {
        return targetStatus;
    }
}
