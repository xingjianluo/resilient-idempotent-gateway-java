package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等记录存储抽象。
 */
public interface IdempotencyStore {

    /**
     * 读取记录；键过期视为不存在。实现可在存储故障时抛出
     * {@link StoreAccessException}。
     */
    Optional<IdempotencyRecord> find(String callerId, String key, Duration ttl);

    /**
     * 读取记录（即使终态已过期也返回），不做惰性删除。
     * 入口据此区分“过期后重放”（KEY_EXPIRED 拒绝）与“全新请求”（允许占位）。
     * IN_FLIGHT 记录不受 TTL 限制（由 inFlightStaleTimeout 单独判定）。
     */
    Optional<IdempotencyRecord> findIncludingExpired(String callerId, String key);

    /**
     * 原子占位：当且仅当记录不存在（或已过期）时插入 IN_FLIGHT 记录并返回 true。
     * 并发下必须只有一个调用方返回 true。
     */
    boolean claim(IdempotencyRecord record, Duration ttl);

    /**
     * 原子接管一个 IN_FLIGHT 卡死占位：仅当当前记录仍是 {@code expected} 且
     * IN_FLIGHT 状态时替换为 {@code replacement}。
     *
     * @return 是否接管成功
     */
    boolean takeoverStaleInFlight(IdempotencyRecord expected, IdempotencyRecord replacement);

    /**
     * 将同一记录迁移到终态。只有成功 claim/接管的持有者会调用。
     */
    void complete(IdempotencyRecord record, Duration ttl);
}
