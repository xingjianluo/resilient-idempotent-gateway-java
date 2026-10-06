package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRejectCode;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRejectedException;

/**
 * 对账回写被确定性拒绝。当拒绝原因是“结论冲突”时，携带已生效的胜出方元数据，
 * 使落败方能看出自己是被谁抢先的。
 */
public class ReconciliationRejectedException extends IdempotencyRejectedException {

    private final ReconciliationInfo winner;

    public ReconciliationRejectedException(IdempotencyRejectCode rejectCode, String message, String traceId) {
        this(rejectCode, message, traceId, null);
    }

    public ReconciliationRejectedException(IdempotencyRejectCode rejectCode, String message,
                                           String traceId, ReconciliationInfo winner) {
        super(rejectCode, message, traceId);
        this.winner = winner;
    }

    /** 结论冲突时实际生效的胜出回写信息；非冲突拒绝时为 null。 */
    public ReconciliationInfo getWinner() {
        return winner;
    }
}
