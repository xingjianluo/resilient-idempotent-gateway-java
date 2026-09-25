package com.github.xingjianluo.resilientidempotentgatewayjava.command;

import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamCall;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamErrorType;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 本地模拟下游"支付"依赖，不发起任何真实网络调用。
 *
 * <p>由请求中的 {@code scenario} 选择行为：
 * <ul>
 *   <li>{@code success}：稳定成功；</li>
 *   <li>{@code business-error}：确定性业务拒绝（可区分于超时）；</li>
 *   <li>{@code timeout}：阻塞超过超时预算，模拟超时后副作用未知；</li>
 *   <li>{@code flaky}：按 {@code orderId} 计数，前 N 次瞬时失败、随后成功，用于验证重试与熔断恢复；</li>
 *   <li>{@code timeout-then-success}：首次阻塞（超时且可能已生效），后续调用成功返回同一单据，
 *       用于验证"部分成功"后不得静默重试、必须标记不确定。</li>
 * </ul>
 * 同时统计真正进入下游的调用次数，验证副作用只发生一次。
 */
@Component
public class SimulatedPaymentClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentClient.class);

    /** 每个订单真正进入下游的执行次数（去重视角的副作用计数）。 */
    private final Map<String, AtomicInteger> executionCounters = new ConcurrentHashMap<>();
    /** flaky 场景每个订单已发生的瞬时失败次数。 */
    private final Map<String, AtomicInteger> transientCounters = new ConcurrentHashMap<>();
    /** timeout-then-success 场景每个订单是否已经产生过下游副作用。 */
    private final Map<String, Boolean> sideEffectDone = new ConcurrentHashMap<>();

    private volatile long timeoutBlockMillis = 500L;
    private volatile int flakyFailures = 2;

    public DownstreamCall<Map<String, Object>> call(CommandRequest request) {
        String scenario = request.scenario() == null ? "success" : request.scenario();
        String orderId = request.orderId() == null ? "unknown" : request.orderId();
        return () -> invoke(scenario, orderId, request.amount());
    }

    private Map<String, Object> invoke(String scenario, String orderId, Integer amount) {
        int execution = executionCounters
                .computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
        log.info("downstream payment ENTER scenario={} orderId={} executionNo={}", scenario, orderId, execution);

        switch (scenario) {
            case "business-error" -> {
                // 结果确定的业务失败：不可重试，与超时明确区分
                throw new DownstreamException(DownstreamErrorType.BUSINESS_REJECTED,
                        "payment rejected by downstream risk control (amount=" + amount + ")",
                        false, true);
            }
            case "timeout" -> {
                // 模拟下游处理慢；是否实际扣款未知
                block(timeoutBlockMillis);
                throw new DownstreamException(DownstreamErrorType.TIMEOUT,
                        "downstream did not respond in time", false, false);
            }
            case "timeout-then-success" -> {
                if (!sideEffectDone.containsKey(orderId)) {
                    sideEffectDone.put(orderId, Boolean.TRUE);
                    // 首次：已产生副作用，但响应未能按时返回
                    block(timeoutBlockMillis);
                    throw new DownstreamException(DownstreamErrorType.TIMEOUT,
                            "downstream accepted the charge but response was lost", false, false);
                }
                return paymentResult(orderId, amount, execution, "recovered");
            }
            case "flaky" -> {
                int fails = transientCounters.computeIfAbsent(orderId, k -> new AtomicInteger()).get();
                if (fails < flakyFailures) {
                    transientCounters.get(orderId).incrementAndGet();
                    // 瞬时连接失败：结果确定（未生效）且可重试
                    throw new DownstreamException(DownstreamErrorType.DOWNSTREAM_ERROR,
                            "transient connection reset (fail " + (fails + 1) + "/" + flakyFailures + ")",
                            true, true);
                }
                return paymentResult(orderId, amount, execution, "ok-after-retry");
            }
            case "success" -> {
                return paymentResult(orderId, amount, execution, "ok");
            }
            default -> throw new DownstreamException(DownstreamErrorType.BUSINESS_REJECTED,
                    "unknown scenario: " + scenario, false, true);
        }
    }

    private Map<String, Object> paymentResult(String orderId, Integer amount, int execution, String note) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("amount", amount);
        result.put("charged", true);
        result.put("executionNo", execution);
        result.put("note", note);
        return result;
    }

    private void block(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 测试调参与观测 ----

    public void setTimeoutBlockMillis(long millis) {
        this.timeoutBlockMillis = Math.max(1L, millis);
    }

    public void setFlakyFailures(int flakyFailures) {
        this.flakyFailures = Math.max(0, flakyFailures);
    }

    public int executionCountOf(String orderId) {
        AtomicInteger counter = executionCounters.get(orderId);
        return counter == null ? 0 : counter.get();
    }

    public void resetObservations() {
        executionCounters.clear();
        transientCounters.clear();
        sideEffectDone.clear();
    }
}
