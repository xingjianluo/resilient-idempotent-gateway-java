package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 下游依赖隔离与降级策略可配置项（前缀 gateway.resilience）。
 * 超时、重试、熔断、并发隔离相互独立、互不干扰。
 */
@Component
@ConfigurationProperties(prefix = "gateway.resilience")
public class ResilienceProperties {

    /** 单次调用超时 */
    private Duration timeout = Duration.ofSeconds(2);
    /** 最大尝试次数（含首次，1 表示不重试） */
    private int maxAttempts = 3;
    /** 重试退避初始间隔 */
    private Duration retryBackoff = Duration.ofMillis(50);
    /** 熔断最小请求数，达到后才依据失败率判定 */
    private int circuitMinimumCalls = 5;
    /** 熔断失败率阈值（0.0~1.0） */
    private double circuitFailureRateThreshold = 0.5;
    /** 打开状态持续时间，过后进入半开试探 */
    private Duration circuitOpenDuration = Duration.ofSeconds(10);
    /** 半开状态允许的试探请求数 */
    private int circuitTrialRequests = 2;
    /** 并发隔离最大在途调用数 */
    private int bulkheadMaxConcurrent = 10;
    /** 隔离线程池大小（按依赖隔离，互不干扰） */
    private int executorPoolSize = 20;

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getRetryBackoff() { return retryBackoff; }
    public void setRetryBackoff(Duration retryBackoff) { this.retryBackoff = retryBackoff; }
    public int getCircuitMinimumCalls() { return circuitMinimumCalls; }
    public void setCircuitMinimumCalls(int circuitMinimumCalls) { this.circuitMinimumCalls = circuitMinimumCalls; }
    public double getCircuitFailureRateThreshold() { return circuitFailureRateThreshold; }
    public void setCircuitFailureRateThreshold(double v) { this.circuitFailureRateThreshold = v; }
    public Duration getCircuitOpenDuration() { return circuitOpenDuration; }
    public void setCircuitOpenDuration(Duration circuitOpenDuration) { this.circuitOpenDuration = circuitOpenDuration; }
    public int getCircuitTrialRequests() { return circuitTrialRequests; }
    public void setCircuitTrialRequests(int circuitTrialRequests) { this.circuitTrialRequests = circuitTrialRequests; }
    public int getBulkheadMaxConcurrent() { return bulkheadMaxConcurrent; }
    public void setBulkheadMaxConcurrent(int bulkheadMaxConcurrent) { this.bulkheadMaxConcurrent = bulkheadMaxConcurrent; }
    public int getExecutorPoolSize() { return executorPoolSize; }
    public void setExecutorPoolSize(int executorPoolSize) { this.executorPoolSize = executorPoolSize; }
}
