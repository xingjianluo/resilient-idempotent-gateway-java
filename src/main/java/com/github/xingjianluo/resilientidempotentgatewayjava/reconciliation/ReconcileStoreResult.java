package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRecord;

/**
 * 存储层对一次对账回写的原子判定结果。
 *
 * @param status  判定结论
 * @param current 判定时的当前记录（未找到时为 null）；用于落败方读取已生效的胜出结论
 */
public record ReconcileStoreResult(Status status, IdempotencyRecord current) {

    public enum Status {
        /** 本次回写已原子生效 */
        APPLIED,
        /** 键不存在 */
        NOT_FOUND,
        /** 键已过期 */
        EXPIRED,
        /** 记录不在“待对账”状态（非 UNCERTAIN，或已被回写过） */
        NOT_PENDING
    }
}
