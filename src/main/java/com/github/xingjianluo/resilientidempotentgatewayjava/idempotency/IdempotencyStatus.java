package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/**
 * 幂等记录的生命周期状态。
 */
public enum IdempotencyStatus {
    /** 首个请求正在执行，尚未得到终态 */
    IN_FLIGHT,
    /** 业务已成功完成，结果可稳定重放 */
    SUCCEEDED,
    /** 确定性业务失败（副作用未发生或已确定回滚），失败结论可稳定重放 */
    FAILED,
    /** 结果不确定（下游可能已执行），禁止静默重试，只能返回不确定提示 */
    UNCERTAIN
}
