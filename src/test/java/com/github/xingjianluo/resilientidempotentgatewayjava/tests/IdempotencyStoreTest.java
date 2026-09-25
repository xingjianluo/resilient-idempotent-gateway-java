package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.resilientidempotentgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.AcquireOutcome;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.AcquireResult;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotentRecord;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.RequestStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.tests.support.TestLog;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 幂等存储：重复收敛、并发不重复执行、过期与载荷冲突拒绝、存储不可用降级。 */
class IdempotencyStoreTest {

    private GatewayProperties props(long ttlSeconds, long waitMillis) {
        GatewayProperties p = new GatewayProperties();
        p.getIdempotency().setTtlSeconds(ttlSeconds);
        p.getIdempotency().setAcquireWaitMillis(waitMillis);
        return p;
    }

    @Test
    void duplicateAfterCompletionCollapsesToReplay() {
        TestLog.section("重复提交：完成后同键同载荷收敛为 REPLAY");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(props(60, 200));

        AcquireResult first = store.acquire("callerA", "key-1", "hash-1", 200);
        assertEquals(AcquireOutcome.ACCEPTED, first.outcome());
        store.complete(first.record(), RequestStatus.SUCCESS, "data", null);

        AcquireResult second = store.acquire("callerA", "key-1", "hash-1", 200);
        TestLog.basis("同 caller + 同键 + 同 hash + 未过期 => REPLAY");
        assertEquals(AcquireOutcome.REPLAY, second.outcome());
        assertEquals(RequestStatus.SUCCESS, second.record().getStatus());
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        TestLog.section("同键不同载荷：拒绝且不覆盖原记录");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(props(60, 200));
        AcquireResult first = store.acquire("c", "k", "hash-A", 200);
        store.complete(first.record(), RequestStatus.SUCCESS, "A", null);

        AcquireResult mismatch = store.acquire("c", "k", "hash-B", 200);
        TestLog.basis("载荷哈希 hash-B != 已存 hash-A => PAYLOAD_MISMATCH");
        assertEquals(AcquireOutcome.PAYLOAD_MISMATCH, mismatch.outcome());

        AcquireResult stillOriginal = store.acquire("c", "k", "hash-A", 200);
        assertEquals(AcquireOutcome.REPLAY, stillOriginal.outcome(), "原记录必须保留");
    }

    @Test
    void expiredRecordRejectsReplay() throws InterruptedException {
        TestLog.section("过期重放：TTL 之后同键请求返回 EXPIRED_REPLAY");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(props(1, 200));
        AcquireResult first = store.acquire("c", "expired-key", "h", 200);
        store.complete(first.record(), RequestStatus.SUCCESS, "d", null);
        TestLog.input("TTL=1 秒");
        Thread.sleep(1100);

        AcquireResult replay = store.acquire("c", "expired-key", "h", 200);
        TestLog.basis("当前时间 >= expiresAt => EXPIRED_REPLAY（可区分于普通 REPLAY）");
        assertEquals(AcquireOutcome.EXPIRED_REPLAY, replay.outcome());
    }

    @Test
    void concurrentSubmissionsExecuteExactlyOnce() throws Exception {
        TestLog.section("并发提交：N 个同键请求只有 1 个 ACCEPTED，其余收敛，不重复执行");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(props(60, 2000));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger replay = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();

        Callable<Void> task = () -> {
            start.await();
            AcquireResult r = store.acquire("callerX", "hot-key", "same-hash", 2000);
            if (r.outcome() == AcquireOutcome.ACCEPTED) {
                accepted.incrementAndGet();
                Thread.sleep(80); // 模拟业务耗时
                store.complete(r.record(), RequestStatus.SUCCESS, "result", null);
            } else if (r.outcome() == AcquireOutcome.REPLAY) {
                replay.incrementAndGet();
            } else if (r.outcome() == AcquireOutcome.CONCURRENT) {
                concurrent.incrementAndGet();
            }
            return null;
        };

        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();

        TestLog.transition("ACCEPTED=%d, REPLAY=%d, CONCURRENT=%d".formatted(
                accepted.get(), replay.get(), concurrent.get()));
        TestLog.basis("ACCEPTED 必须恰为 1；其余为 REPLAY 或 CONCURRENT，无任何重复执行");
        assertEquals(1, accepted.get(), "副作用只允许发生一次");
        assertEquals(threads - 1, replay.get() + concurrent.get());
    }

    @Test
    void concurrentSubmissionBeyondWaitWindowGetsConflict() throws Exception {
        TestLog.section("并发冲突：首个请求长于等待窗口时，重复请求得到 CONCURRENT");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(props(60, 100));
        CountDownLatch firstEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        pool.submit(() -> {
            AcquireResult r = store.acquire("c", "long", "h", 100);
            if (r.outcome() == AcquireOutcome.ACCEPTED) {
                firstEntered.countDown();
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                store.complete(r.record(), RequestStatus.SUCCESS, "d", null);
            }
        });

        assertTrue(firstEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        AcquireResult rival = store.acquire("c", "long", "h", 100);
        TestLog.basis("等待窗口 100ms 内首个请求未完成 => CONCURRENT_SUBMISSION");
        assertEquals(AcquireOutcome.CONCURRENT, rival.outcome());
        pool.shutdownNow();
    }

    @Test
    void uncertainRecordBlocksBlindReplay() {
        TestLog.section("不确定重放：UNCERTAIN 记录再次请求返回 UNCERTAIN_REPLAY");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(props(60, 200));
        AcquireResult first = store.acquire("c", "uncertain", "h", 200);
        store.complete(first.record(), RequestStatus.UNCERTAIN, null, "DOWNSTREAM_TIMEOUT");

        AcquireResult again = store.acquire("c", "uncertain", "h", 200);
        assertEquals(AcquireOutcome.UNCERTAIN_REPLAY, again.outcome());
        assertNotNull(again.record());
    }

    @Test
    void storeUnavailableReturnsDistinguishableDegradation() {
        TestLog.section("存储不可用：fail-closed，返回 STORE_UNAVAILABLE");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(props(60, 200));
        store.setAvailable(false);
        AcquireResult r = store.acquire("c", "k", "h", 200);
        TestLog.basis("存储宕机时绝不能放行执行，否则可能产生重复副作用");
        assertEquals(AcquireOutcome.STORE_UNAVAILABLE, r.outcome());
    }

    @Test
    void callerIdsAreIsolated() {
        TestLog.section("调用方隔离：不同 callerId 复用同键互不影响");
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(props(60, 200));
        store.complete(store.acquire("alice", "k", "h", 200).record(),
                RequestStatus.SUCCESS, "a", null);
        AcquireResult bob = store.acquire("bob", "k", "h", 200);
        assertEquals(AcquireOutcome.ACCEPTED, bob.outcome(), "不同调用方同键应视为首次请求");
    }
}
