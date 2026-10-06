package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

/**
 * 对账回写的拒绝结论，每种场景对应可区分的错误码与 HTTP 状态。
 */
public enum ReconciliationRejectCode {

    /** 键不存在（从未受理或已被彻底清除） */
    KEY_NOT_FOUND("RECONCILIATION_KEY_NOT_FOUND", 404),
    /** 键已过期，不再允许回写 */
    KEY_EXPIRED("RECONCILIATION_KEY_EXPIRED", 409),
    /** 记录不在“结果未知、尚未对账”状态（IN_FLIGHT / 已是终态） */
    NOT_PENDING("RECONCILIATION_NOT_PENDING", 409),
    /** 已被他人以不同结论抢先回写，本次落败 */
    VERDICT_CONFLICT("RECONCILIATION_VERDICT_CONFLICT", 409),
    /** 幂等存储暂时不可用，回写被拒绝（无副作用） */
    STORE_UNAVAILABLE("RECONCILIATION_STORE_UNAVAILABLE", 503);

    private final String code;
    private final int httpStatus;

    ReconciliationRejectCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
