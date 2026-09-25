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
}
