package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.ReconciliationInfo;

/**
 * 对账回写给出的确定性拒绝结论（不携带内部堆栈信息）。
 * 结论冲突落败时携带抢先者的回写信息，便于调用方判定“被谁抢先”。
 */
public class ReconciliationRejectedException extends RuntimeException {

    private final ReconciliationRejectCode rejectCode;
    private final String traceId;
    private final ReconciliationInfo winner;

    public ReconciliationRejectedException(ReconciliationRejectCode rejectCode, String message,
                                           String traceId) {
        this(rejectCode, message, traceId, null);
    }

    public ReconciliationRejectedException(ReconciliationRejectCode rejectCode, String message,
                                           String traceId, ReconciliationInfo winner) {
        super(message);
        this.rejectCode = rejectCode;
        this.traceId = traceId;
        this.winner = winner;
    }

    public ReconciliationRejectCode getRejectCode() {
        return rejectCode;
    }

    public String getTraceId() {
        return traceId;
    }

    /** 结论冲突时抢先生效的回写信息；其他场景为 null。 */
    public ReconciliationInfo getWinner() {
        return winner;
    }
}
