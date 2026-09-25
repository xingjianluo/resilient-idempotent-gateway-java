package com.github.xingjianluo.resilientidempotentgatewayjava.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关全部可配置项：幂等收敛与下游隔离/降级参数。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Idempotency idempotency = new Idempotency();
    private Resilience resilience = new Resilience();

    public Idempotency getIdempotency() { return idempotency; }
    public void setIdempotency(Idempotency idempotency) { this.idempotency = idempotency; }
    public Resilience getResilience() { return resilience; }
    public void setResilience(Resilience resilience) { this.resilience = resilience; }

    /** 幂等入口配置。 */
    public static class Idempotency {
        /** 幂等记录存活时间（秒），过期后同键重放将被拒绝。 */
        private long ttlSeconds = 300;
        /** 并发提交时等待首个请求完成的最长时间（毫秒），超时返回并发冲突。 */
        private long acquireWaitMillis = 200;

        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public long getAcquireWaitMillis() { return acquireWaitMillis; }
        public void setAcquireWaitMillis(long acquireWaitMillis) { this.acquireWaitMillis = acquireWaitMillis; }
    }

    /** 下游韧性配置，按依赖名区分，"default" 为兜底规则。 */
    public static class Resilience {
        private Map<String, Rule> dependencies = new LinkedHashMap<>();

        public Map<String, Rule> getDependencies() { return dependencies; }
        public void setDependencies(Map<String, Rule> dependencies) { this.dependencies = dependencies; }

        public Rule ruleFor(String dependency) {
            Rule rule = dependencies.get(dependency);
            if (rule == null) {
                rule = dependencies.get("default");
            }
            return rule != null ? rule : new Rule();
        }
    }

    /** 单个下游依赖的超时/重试/熔断/隔离规则，四类策略互不干扰。 */
    public static class Rule {
        private long callTimeoutMillis = 1000;
        private int maxAttempts = 2;
        private long backoffMillis = 20;
        private int failureThreshold = 5;
        private long openMillis = 2000;
        private int bulkheadMaxConcurrent = 10;

        public long getCallTimeoutMillis() { return callTimeoutMillis; }
        public void setCallTimeoutMillis(long v) { this.callTimeoutMillis = v; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { this.maxAttempts = v; }
        public long getBackoffMillis() { return backoffMillis; }
        public void setBackoffMillis(long v) { this.backoffMillis = v; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int v) { this.failureThreshold = v; }
        public long getOpenMillis() { return openMillis; }
        public void setOpenMillis(long v) { this.openMillis = v; }
        public int getBulkheadMaxConcurrent() { return bulkheadMaxConcurrent; }
        public void setBulkheadMaxConcurrent(int v) { this.bulkheadMaxConcurrent = v; }
    }
}
