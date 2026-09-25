package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.BusinessTerminal;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRejectCode;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRejectedException;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyOutcome;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotentCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 幂等入口单元测试。
 * 断言稳定可复现；测试名与日志体现输入、判定依据与状态迁移。
 */
class IdempotentCommandServiceTest {

    public record Order(String id) {
    }

    private MutableClock clock;
    private InMemoryIdempotencyStore store;
    private IdempotentCommandService service;
    private IdempotencyProperties properties;

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-09-26T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        store = new InMemoryIdempotencyStore(clock);
        properties = new IdempotencyProperties();
        properties.setTtl(Duration.ofMinutes(10));
        properties.setInFlightStaleTimeout(Duration.ofMinutes(1));
        service = new IdempotentCommandService(store, properties, new ObjectMapper(), clock);
    }

    @Test
    @DisplayName("重复提交：副作用只发生一次，第二次重放稳定结果")
    void duplicateRequestSideEffectOnceAndStableReplay() {
        AtomicInteger sideEffects = new AtomicInteger();

        IdempotencyOutcome<Order> first = service.execute(
                "caller-A", "key-1", "hash-1", "trace-1", Order.class,
                () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("ORD-1"));
                });

        IdempotencyOutcome<Order> second = service.execute(
                "caller-A", "key-1", "hash-1", "trace-2", Order.class,
                () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("ORD-DIFFERENT"));
                });

        System.out.println("[输入] 同 caller/key/hash 两次提交；[判定依据] 第二次命中 SUCCEEDED 终态重放；"
                + "[状态迁移] IN_FLIGHT->SUCCEEDED，第二次 REPLAYED");
        assertEquals(IdempotencyOutcome.Kind.EXECUTED, first.getKind());
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, second.getKind());
        assertEquals(IdempotencyStatus.SUCCEEDED, second.getStatus());
        assertEquals("ORD-1", second.getResult().id(), "重放必须返回首次的稳定结果");
        assertEquals(1, sideEffects.get(), "副作用只允许发生一次");
    }

    @Test
    @DisplayName("不同调用方使用相同键：互不收敛（维度为 callerId+key）")
    void differentCallersSameKeyAreIndependent() {
        AtomicInteger sideEffects = new AtomicInteger();
        for (String caller : new String[]{"A", "B"}) {
            IdempotencyOutcome<Order> o = service.execute(caller, "shared-key", "h", "t-" + caller,
                    Order.class, () -> {
                        sideEffects.incrementAndGet();
                        return BusinessTerminal.success(new Order("ORD-" + caller));
                    });
            assertEquals(IdempotencyStatus.SUCCEEDED, o.getStatus());
        }
        System.out.println("[输入] caller=A/B 使用同一 key；[判定依据] 收敛维度含 callerId；[状态] 各执行一次");
        assertEquals(2, sideEffects.get());
    }

    @Test
    @DisplayName("同键不同载荷：拒绝 PAYLOAD_MISMATCH，且不执行第二次")
    void sameKeyDifferentPayloadRejected() {
        AtomicInteger sideEffects = new AtomicInteger();
        service.execute("caller-A", "key-2", "hash-alpha", "t", Order.class,
                () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("X"));
                });

        IdempotencyRejectedException ex = assertThrows(IdempotencyRejectedException.class,
                () -> service.execute("caller-A", "key-2", "hash-beta", "t2", Order.class,
                        () -> {
                            sideEffects.incrementAndGet();
                            return BusinessTerminal.success(new Order("Y"));
                        }));

        System.out.println("[输入] 同 key，hash-alpha 后提交 hash-beta；[判定依据] payloadHash 不一致；"
                + "[结论] PAYLOAD_MISMATCH");
        assertEquals(IdempotencyRejectCode.PAYLOAD_MISMATCH, ex.getRejectCode());
        assertEquals(1, sideEffects.get());
    }

    @Test
    @DisplayName("键过期后重放：拒绝 KEY_EXPIRED，需使用新键")
    void expiredKeyReplayRejected() {
        service.execute("c", "key-ttl", "h", "t", Order.class,
                () -> BusinessTerminal.success(new Order("Z")));

        clock.advance(Duration.ofMinutes(11)); // 超过 TTL

        IdempotencyRejectedException ex = assertThrows(IdempotencyRejectedException.class,
                () -> service.execute("c", "key-ttl", "h", "t2", Order.class,
                        () -> BusinessTerminal.success(new Order("NEW"))));

        System.out.println("[输入] 终态后时钟前进超过 TTL 再重放；[判定依据] completedAt+ttl < now；"
                + "[结论] KEY_EXPIRED");
        assertEquals(IdempotencyRejectCode.KEY_EXPIRED, ex.getRejectCode());
    }

    @Test
    @DisplayName("过期后：同键重放被拒绝；使用新键可正常发起全新请求")
    void afterExpiryOnlyNewKeyExecutes() {
        service.execute("c", "k", "h", "t", Order.class,
                () -> BusinessTerminal.success(new Order("OLD")));
        clock.advance(Duration.ofMinutes(11));

        assertThrows(IdempotencyRejectedException.class,
                () -> service.execute("c", "k", "h", "t2", Order.class,
                        () -> BusinessTerminal.success(new Order("NEW"))));

        IdempotencyOutcome<Order> fresh = service.execute("c", "k-NEW", "h", "t3", Order.class,
                () -> BusinessTerminal.success(new Order("NEW")));
        assertEquals(IdempotencyOutcome.Kind.EXECUTED, fresh.getKind());
        assertEquals("NEW", fresh.getResult().id());
    }

    @Test
    @DisplayName("并发重复提交：N 个同键并发只有 1 个执行，其余得到 CONCURRENT_IN_FLIGHT 或重放")
    void concurrentDuplicatesExecuteExactlyOnce() throws Exception {
        int threads = 16;
        AtomicInteger sideEffects = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        service.execute("c", "conc-key", "same-hash", "trace-" + idx, Order.class, () -> {
                            sideEffects.incrementAndGet();
                            inside.countDown();
                            // 拉长首个请求，确保其余请求观察到 IN_FLIGHT
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            return BusinessTerminal.success(new Order("ONE"));
                        });
                    } catch (IdempotencyRejectedException expected) {
                        // 预期：CONCURRENT_IN_FLIGHT
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            assertTrue(inside.await(2, TimeUnit.SECONDS), "首个请求应当进入业务执行");
            // 给其余线程时间完成占位竞争
            Thread.sleep(200);
            release.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        System.out.println("[输入] " + threads + " 个线程同 caller/key/hash 并发；[判定依据] claim 原子性；"
                + "[状态迁移] 1 个 IN_FLIGHT->SUCCEEDED，其余 CONCURRENT_IN_FLIGHT");
        assertEquals(1, sideEffects.get(), "并发下副作用必须恰好一次");

        // 首个请求完成后，新的重复请求应重放，而不是再次执行
        IdempotencyOutcome<Order> after = service.execute("c", "conc-key", "same-hash", "trace-after",
                Order.class, () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("TWO"));
                });
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, after.getKind());
        assertEquals("ONE", after.getResult().id());
        assertEquals(1, sideEffects.get());
    }

    @Test
    @DisplayName("存储读不可用：fail-closed，拒绝 STORE_UNAVAILABLE，不执行业务")
    void storeUnavailableOnReadFailsClosed() {
        store.setAvailable(false);
        AtomicInteger sideEffects = new AtomicInteger();
        IdempotencyRejectedException ex = assertThrows(IdempotencyRejectedException.class,
                () -> service.execute("c", "k", "h", "t", Order.class, () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("X"));
                }));
        System.out.println("[输入] 存储置为不可用后发起请求；[判定依据] find 抛 StoreAccessException；"
                + "[结论] STORE_UNAVAILABLE，fail-closed 不产生副作用");
        assertEquals(IdempotencyRejectCode.STORE_UNAVAILABLE, ex.getRejectCode());
        assertEquals(0, sideEffects.get());
        assertEquals(503, ex.getRejectCode().httpStatus());
    }

    @Test
    @DisplayName("业务返回 UNCERTAIN：重复请求重放不确定结论，绝不静默重试")
    void uncertainOutcomeIsReplayedNotRetried() {
        AtomicInteger sideEffects = new AtomicInteger();
        IdempotencyOutcome<Order> first = service.execute("c", "unc-key", "h", "t1", Order.class,
                () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.uncertain("DOWNSTREAM_TIMEOUT");
                });
        IdempotencyOutcome<Order> second = service.execute("c", "unc-key", "h", "t2", Order.class,
                () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("SHOULD-NOT-HAPPEN"));
                });
        System.out.println("[输入] 首次下游超时标记 UNCERTAIN 后重复提交；[判定依据] 命中 UNCERTAIN 终态；"
                + "[状态] 第二次 REPLAYED/UNCERTAIN，不重试");
        assertEquals(IdempotencyStatus.UNCERTAIN, first.getStatus());
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, second.getKind());
        assertEquals(IdempotencyStatus.UNCERTAIN, second.getStatus());
        assertEquals("DOWNSTREAM_TIMEOUT", second.getErrorCode());
        assertNull(second.getResult());
        assertEquals(1, sideEffects.get());
    }

    @Test
    @DisplayName("确定性业务失败：FAILED 终态可稳定重放，不再次执行")
    void deterministicFailureIsReplayed() {
        AtomicInteger calls = new AtomicInteger();
        IdempotencyOutcome<Order> f1 = service.execute("c", "fail-key", "h", "t", Order.class,
                () -> {
                    calls.incrementAndGet();
                    return BusinessTerminal.failed("INVALID_AMOUNT");
                });
        IdempotencyOutcome<Order> f2 = service.execute("c", "fail-key", "h", "t2", Order.class,
                () -> {
                    calls.incrementAndGet();
                    return BusinessTerminal.success(new Order("NO"));
                });
        System.out.println("[输入] 确定性业务失败后重复提交；[判定依据] FAILED 为终态；[状态] 重放 FAILED");
        assertEquals(IdempotencyStatus.FAILED, f1.getStatus());
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, f2.getKind());
        assertEquals(IdempotencyStatus.FAILED, f2.getStatus());
        assertEquals("INVALID_AMOUNT", f2.getErrorCode());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("IN_FLIGHT 卡死占位超时后：新请求原子接管并执行")
    void staleInFlightIsTakenOver() {
        // 第一个请求占位但“崩溃”（不完成）
        store.claim(com.github.xingjianluo.resilientidempotentgatewayjava.idempotency
                .IdempotencyRecord.inFlight("c", "stale", "h", clock.instant()), properties.getTtl());

        clock.advance(Duration.ofMinutes(2)); // 超过 inFlightStaleTimeout

        AtomicInteger calls = new AtomicInteger();
        IdempotencyOutcome<Order> o = service.execute("c", "stale", "h", "t", Order.class,
                () -> {
                    calls.incrementAndGet();
                    return BusinessTerminal.success(new Order("RECOVERED"));
                });
        System.out.println("[输入] IN_FLIGHT 超过卡死阈值后新请求到达；[判定依据] takeoverStaleInFlight 原子成功；"
                + "[状态迁移] 卡死 IN_FLIGHT -> 新 IN_FLIGHT -> SUCCEEDED");
        assertEquals(IdempotencyOutcome.Kind.EXECUTED, o.getKind());
        assertEquals("RECOVERED", o.getResult().id());
        assertEquals(1, calls.get());
    }
}
