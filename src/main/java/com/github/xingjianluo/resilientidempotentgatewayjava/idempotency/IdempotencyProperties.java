package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 幂等入口可配置项（前缀 gateway.idempotency）。
 */
@Component
@ConfigurationProperties(prefix = "gateway.idempotency")
public class IdempotencyProperties {

    /** 终态记录存活时间，过期后同键重放将被拒绝（KEY_EXPIRED） */
    private Duration ttl = Duration.ofHours(24);

    /**
     * IN_FLIGHT 占位被视为“持有者已崩溃”的超时；超时后允许新请求抢占。
     * 正常的同键并发（在此时长内）一律收敛为 CONCURRENT_IN_FLIGHT。
     */
    private Duration inFlightStaleTimeout = Duration.ofSeconds(30);

    /**
     * 待对账（UNCERTAIN 且未回写）记录的积压上限。达到上限后入口降级：
     * 拒绝受理全新键（RECONCILE_BACKLOG_FULL），已有键的重放与对账回写不受影响。
     */
    private long reconciliationMaxPending = 10_000;

    /**
     * 积压观测口径：是否把已过期但尚未清理的待对账记录也计入积压量。
     * 默认 false（只统计仍可回写的未过期待对账记录）。
     */
    private boolean reconciliationBacklogIncludeExpired = false;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getInFlightStaleTimeout() {
        return inFlightStaleTimeout;
    }

    public void setInFlightStaleTimeout(Duration inFlightStaleTimeout) {
        this.inFlightStaleTimeout = inFlightStaleTimeout;
    }

    public long getReconciliationMaxPending() {
        return reconciliationMaxPending;
    }

    public void setReconciliationMaxPending(long reconciliationMaxPending) {
        this.reconciliationMaxPending = reconciliationMaxPending;
    }

    public boolean isReconciliationBacklogIncludeExpired() {
        return reconciliationBacklogIncludeExpired;
    }

    public void setReconciliationBacklogIncludeExpired(boolean reconciliationBacklogIncludeExpired) {
        this.reconciliationBacklogIncludeExpired = reconciliationBacklogIncludeExpired;
    }
}
