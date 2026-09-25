package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.CircuitBreaker;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.CircuitState;
import com.github.xingjianluo.resilientidempotentgatewayjava.tests.support.TestLog;
import org.junit.jupiter.api.Test;

/** 熔断器：跳闸、冷却、HALF_OPEN 试探成功关闭/失败重开，两类恢复必须可区分。 */
class CircuitBreakerTest {

    @Test
    void opensAfterThresholdThenProbeSuccessCloses() {
        TestLog.section("熔断：阈值跳闸 -> 冷却 -> 试探成功关闭");
        CircuitBreaker cb = new CircuitBreaker("dep", 3, 100);
        long t = 0L;

        TestLog.input("failureThreshold=3, openMillis=100");
        cb.recordFailure(t);
        cb.recordFailure(t);
        TestLog.basis("2 次失败 < 阈值 3，仍应放行");
        assertTrue(cb.tryAcquirePermission(t), "未达阈值应保持 CLOSED");
        assertEquals(CircuitState.CLOSED, cb.currentState());

        cb.recordFailure(t);
        TestLog.transition("第 3 次失败：CLOSED -> OPEN");
        assertEquals(CircuitState.OPEN, cb.currentState());
        assertFalse(cb.tryAcquirePermission(t + 50), "OPEN 冷却内必须拒绝");

        TestLog.transition("冷却结束，首个请求成为试探：OPEN -> HALF_OPEN");
        assertTrue(cb.tryAcquirePermission(t + 101), "冷却后应放行唯一试探");
        assertEquals(CircuitState.HALF_OPEN, cb.currentState());
        assertFalse(cb.tryAcquirePermission(t + 101), "试探在途时其它请求必须拒绝");

        cb.recordSuccess();
        TestLog.transition("试探成功：HALF_OPEN -> CLOSED，失败计数清零");
        assertEquals(CircuitState.CLOSED, cb.currentState());
        assertEquals(0, cb.consecutiveFailures());
        assertTrue(cb.tryAcquirePermission(t + 102));
    }

    @Test
    void failedProbeReopensAndRestartsCooldown() {
        TestLog.section("熔断：试探失败必须重新 OPEN 并重置冷却");
        CircuitBreaker cb = new CircuitBreaker("dep", 1, 100);
        long t = 0L;

        cb.recordFailure(t);
        assertEquals(CircuitState.OPEN, cb.currentState());
        assertTrue(cb.tryAcquirePermission(t + 100), "进入试探");
        assertEquals(CircuitState.HALF_OPEN, cb.currentState());

        cb.recordFailure(t + 100);
        TestLog.transition("试探失败：HALF_OPEN -> OPEN，openedAt 重置为 100");
        assertEquals(CircuitState.OPEN, cb.currentState());
        assertFalse(cb.tryAcquirePermission(t + 150), "冷却只过了 50ms，仍须拒绝");
        assertTrue(cb.tryAcquirePermission(t + 201), "重新冷却 100ms 后才允许下一次试探");
    }
}
