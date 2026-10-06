package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/**
 * 幂等入口拒绝/降级结论，每种场景对应可区分的错误码与 HTTP 状态。
 */
public enum IdempotencyRejectCode {
    /** 同一幂等键携带了不同载荷 */
    PAYLOAD_MISMATCH("IDEMPOTENCY_PAYLOAD_MISMATCH", 422),
    /** 幂等键记录已过期，不允许重放，需使用新键 */
    KEY_EXPIRED("IDEMPOTENCY_KEY_EXPIRED", 409),
    /** 同键并发请求，首个请求仍在执行中 */
    CONCURRENT_IN_FLIGHT("IDEMPOTENCY_CONCURRENT_IN_FLIGHT", 409),
    /** 幂等存储暂时不可用，入口 fail-closed 降级，不执行业务 */
    STORE_UNAVAILABLE("IDEMPOTENCY_STORE_UNAVAILABLE", 503),
    /** 对账回写：键不存在 */
    RECONCILE_KEY_NOT_FOUND("RECONCILE_KEY_NOT_FOUND", 404),
    /** 对账回写：键已过期 */
    RECONCILE_KEY_EXPIRED("RECONCILE_KEY_EXPIRED", 410),
    /** 对账回写：记录不在“待对账”状态（非 UNCERTAIN，或已成功/失败） */
    RECONCILE_NOT_PENDING("RECONCILE_NOT_PENDING", 409),
    /** 对账回写：与已生效的回写结论矛盾，本次落败 */
    RECONCILE_CONFLICT("RECONCILE_CONFLICT", 409),
    /** 待对账积压达到上限，入口降级暂停受理新键（已有键的重放/回写不受影响） */
    RECONCILE_BACKLOG_FULL("RECONCILE_BACKLOG_FULL", 503);

    private final String code;
    private final int httpStatus;

    IdempotencyRejectCode(String code, int httpStatus) {
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
