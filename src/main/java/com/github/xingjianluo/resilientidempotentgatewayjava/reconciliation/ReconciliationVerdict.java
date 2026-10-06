package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStatus;

/**
 * 对账回写的权威结论：确认已成功 / 确认未成功。
 */
public enum ReconciliationVerdict {

    CONFIRMED_SUCCEEDED(IdempotencyStatus.SUCCEEDED),
    CONFIRMED_FAILED(IdempotencyStatus.FAILED);

    private final IdempotencyStatus terminalStatus;

    ReconciliationVerdict(IdempotencyStatus terminalStatus) {
        this.terminalStatus = terminalStatus;
    }

    /** 回写后记录迁移到的稳定终态。 */
    public IdempotencyStatus terminalStatus() {
        return terminalStatus;
    }
}
