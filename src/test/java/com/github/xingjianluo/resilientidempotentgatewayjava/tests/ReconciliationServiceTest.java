package com.github.xingjianluo.resilientidempotentgatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.BusinessTerminal;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyOutcome;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRecord;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRejectCode;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRejectedException;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotentCommandService;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationOutcome;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationRejectCode;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationRejectedException;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationService;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对账回写单元测试：回写收敛、并发胜负、冲突判定、区分拒绝、积压上限降级、遗留记录兼容。
 * 每个用例打印「输入 / 判定依据 / 状态迁移」。
 */
class ReconciliationServiceTest {

    public record Order(String id) {
    }

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-07T00:00:00Z");

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

    private MutableClock clock;
    private InMemoryIdempotencyStore store;
    private IdempotencyProperties properties;
    private IdempotentCommandService commandService;
    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        store = new InMemoryIdempotencyStore(clock);
        properties = new IdempotencyProperties();
        properties.setTtl(Duration.ofMinutes(10));
        properties.setInFlightStaleTimeout(Duration.ofMinutes(1));
        properties.setMaxPendingReconciliation(100);
        commandService = new IdempotentCommandService(store, properties, new ObjectMapper(), clock);
        reconciliationService = new ReconciliationService(store, properties, clock);
    }

    /** 制造一条“结果未知、尚未对账”的记录（下游超时场景）。 */
    private void makeUncertain(String caller, String key) {
        IdempotencyOutcome<Order> o = commandService.execute(caller, key, "hash-" + key, "trace-exec-" + key,
                Order.class, () -> BusinessTerminal.uncertain("DOWNSTREAM_TIMEOUT"));
        assertEquals(IdempotencyStatus.UNCERTAIN, o.getStatus());
    }

    @Test
    @DisplayName("回写确认成功：之后重复提交直接返回 SUCCEEDED 稳定终态并标重放，不再回未知")
    void reconcileSucceededThenReplayStable() {
        makeUncertain("c", "k-ok");

        ReconciliationOutcome outcome = reconciliationService.reconcile(
                "c", "k-ok", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                "ops-li", "ledger-statement-20261007", "trace-rec-1");
        System.out.println("[输入] UNCERTAIN 记录回写 CONFIRMED_SUCCEEDED；[判定依据] 状态=UNCERTAIN 且未对账；"
                + "[状态迁移] UNCERTAIN->SUCCEEDED，记录回写人/时间/依据");
        assertTrue(outcome.isApplied());
        assertEquals(IdempotencyStatus.SUCCEEDED, outcome.getStatus());
        assertEquals("ops-li", outcome.getReconciliation().getReconciledBy());
        assertEquals("trace-rec-1", outcome.getReconciliation().getTraceId());

        AtomicInteger sideEffects = new AtomicInteger();
        IdempotencyOutcome<Order> replay = commandService.execute("c", "k-ok", "hash-k-ok", "trace-replay",
                Order.class, () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("SHOULD-NOT-RUN"));
                });
        System.out.println("[输入] 回写后同键重复提交；[判定依据] 命中回写后的 SUCCEEDED 终态；"
                + "[状态] REPLAYED/SUCCEEDED，业务不再执行");
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, replay.getKind());
        assertEquals(IdempotencyStatus.SUCCEEDED, replay.getStatus());
        assertEquals(0, sideEffects.get(), "回写后重复提交不得再执行业务");
    }

    @Test
    @DisplayName("回写确认未成功：之后重复提交返回 FAILED 稳定终态并标重放")
    void reconcileFailedThenReplayStable() {
        makeUncertain("c", "k-fail");

        ReconciliationOutcome outcome = reconciliationService.reconcile(
                "c", "k-fail", ReconciliationVerdict.CONFIRMED_FAILED,
                "ops-wang", "ledger-no-entry", "trace-rec-2");
        assertTrue(outcome.isApplied());
        assertEquals(IdempotencyStatus.FAILED, outcome.getStatus());

        IdempotencyOutcome<Order> replay = commandService.execute("c", "k-fail", "hash-k-fail", "trace-replay",
                Order.class, () -> BusinessTerminal.success(new Order("NO")));
        System.out.println("[输入] UNCERTAIN 回写 CONFIRMED_FAILED 后重复提交；[判定依据] 命中 FAILED 终态；"
                + "[状态] REPLAYED/FAILED");
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, replay.getKind());
        assertEquals(IdempotencyStatus.FAILED, replay.getStatus());
    }

    @Test
    @DisplayName("原生终态不可被回写改变：SUCCEEDED/FAILED 记录回写一律拒绝 NOT_PENDING")
    void nativeTerminalRecordsCannotBeReconciled() {
        commandService.execute("c", "k-succ", "hash-k-succ", "t", Order.class,
                () -> BusinessTerminal.success(new Order("OK")));
        commandService.execute("c", "k-failed", "hash-k-failed", "t", Order.class,
                () -> BusinessTerminal.failed("INVALID_AMOUNT"));

        ReconciliationRejectedException ex1 = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("c", "k-succ", ReconciliationVerdict.CONFIRMED_FAILED,
                        "ops", "basis", "trace-x"));
        ReconciliationRejectedException ex2 = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("c", "k-failed", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                        "ops", "basis", "trace-y"));

        System.out.println("[输入] 对原生 SUCCEEDED/FAILED 记录回写相反结论；[判定依据] 状态非 UNCERTAIN；"
                + "[结论] NOT_PENDING，原生终态不受影响");
        assertEquals(ReconciliationRejectCode.NOT_PENDING, ex1.getRejectCode());
        assertEquals(ReconciliationRejectCode.NOT_PENDING, ex2.getRejectCode());

        // 原生终态保持不变
        IdempotencyOutcome<Order> replay = commandService.execute("c", "k-succ", "hash-k-succ", "t2",
                Order.class, () -> BusinessTerminal.failed("X"));
        assertEquals(IdempotencyStatus.SUCCEEDED, replay.getStatus());
    }

    @Test
    @DisplayName("并发回写：N 个并发只有一个真正生效，其余拿到已生效结论")
    void concurrentReconcileExactlyOneApplies() throws Exception {
        makeUncertain("c", "k-conc");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger alreadyApplied = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        ReconciliationOutcome o = reconciliationService.reconcile(
                                "c", "k-conc", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                                "ops-" + idx, "basis-" + idx, "trace-conc-" + idx);
                        if (o.isApplied()) {
                            applied.incrementAndGet();
                        } else {
                            alreadyApplied.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("[输入] " + threads + " 线程同键并发回写相同结论；[判定依据] reconcileIfPending 原子判定；"
                + "[状态迁移] 恰好 1 个 UNCERTAIN->SUCCEEDED，其余拿到已生效结论");
        assertEquals(1, applied.get(), "并发回写必须恰好一个生效");
        assertEquals(threads - 1, alreadyApplied.get());
        assertEquals(0, store.pendingReconciliationCount(), "回写后不再计入待对账积压");
    }

    @Test
    @DisplayName("结论冲突：一次说成功一次说失败，只有一个写入，落败方能看出被谁抢先")
    void conflictingVerdictsOnlyOneWins() {
        makeUncertain("c", "k-conflict");

        ReconciliationOutcome winner = reconciliationService.reconcile(
                "c", "k-conflict", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                "ops-first", "statement-A", "trace-win");
        assertTrue(winner.isApplied());

        ReconciliationRejectedException loser = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("c", "k-conflict", ReconciliationVerdict.CONFIRMED_FAILED,
                        "ops-second", "statement-B", "trace-lose"));
        System.out.println("[输入] 先回写 CONFIRMED_SUCCEEDED 再回写 CONFIRMED_FAILED；[判定依据] 已有回写结论且矛盾；"
                + "[结论] VERDICT_CONFLICT，落败方可见抢先者 ops-first/trace-win");
        assertEquals(ReconciliationRejectCode.VERDICT_CONFLICT, loser.getRejectCode());
        assertNotNull(loser.getWinner(), "落败方必须能看到抢先者信息");
        assertEquals("ops-first", loser.getWinner().getReconciledBy());
        assertEquals(IdempotencyStatus.SUCCEEDED, loser.getWinner().getVerdictStatus());
        assertEquals("trace-win", loser.getWinner().getTraceId());
        assertTrue(loser.getMessage().contains("ops-first"), "拒绝信息应指明被谁抢先");

        // 记录保持胜方结论，未被覆盖
        IdempotencyOutcome<Order> replay = commandService.execute("c", "k-conflict", "hash-k-conflict",
                "t", Order.class, () -> BusinessTerminal.failed("X"));
        assertEquals(IdempotencyStatus.SUCCEEDED, replay.getStatus());
    }

    @Test
    @DisplayName("并发冲突回写：混合结论并发，恰好一个生效，其余要么同结论收敛要么冲突落败")
    void concurrentConflictingReconcileSingleWinner() throws Exception {
        makeUncertain("c", "k-mixed");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger sameVerdict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        ReconciliationVerdict v = idx % 2 == 0
                                ? ReconciliationVerdict.CONFIRMED_SUCCEEDED
                                : ReconciliationVerdict.CONFIRMED_FAILED;
                        reconciliationService.reconcile("c", "k-mixed", v,
                                "ops-" + idx, "basis-" + idx, "trace-m-" + idx);
                        // 返回即生效或同结论收敛
                        sameVerdict.incrementAndGet();
                    } catch (ReconciliationRejectedException e) {
                        assertEquals(ReconciliationRejectCode.VERDICT_CONFLICT, e.getRejectCode());
                        assertNotNull(e.getWinner());
                        conflicts.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("[输入] 10 线程混合结论并发回写；[判定依据] 原子回写 + 冲突判定；"
                + "[状态] 仅一个结论生效，矛盾方全部 VERDICT_CONFLICT 且可见抢先者");
        assertEquals(threads, sameVerdict.get() + conflicts.get());
        assertTrue(conflicts.get() > 0, "混合结论下必有冲突落败方");

        // 最终记录只有一个稳定终态
        IdempotencyOutcome<Order> replay = commandService.execute("c", "k-mixed", "hash-k-mixed",
                "t", Order.class, () -> BusinessTerminal.uncertain("X"));
        assertNotEquals(IdempotencyStatus.UNCERTAIN, replay.getStatus());
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, replay.getKind());
    }

    @Test
    @DisplayName("重复回写相同结论：返回已生效结论（applied=false），可安全重试")
    void duplicateReconcileSameVerdictIsSafe() {
        makeUncertain("c", "k-dup");
        ReconciliationOutcome first = reconciliationService.reconcile(
                "c", "k-dup", ReconciliationVerdict.CONFIRMED_SUCCEEDED, "ops-a", "b1", "trace-d1");
        ReconciliationOutcome second = reconciliationService.reconcile(
                "c", "k-dup", ReconciliationVerdict.CONFIRMED_SUCCEEDED, "ops-a", "b1", "trace-d2");
        System.out.println("[输入] 同一回写请求重试两次；[判定依据] 已有相同结论的回写；"
                + "[状态] 第二次 applied=false，返回首次生效结论");
        assertTrue(first.isApplied());
        assertFalse(second.isApplied());
        assertEquals(IdempotencyStatus.SUCCEEDED, second.getStatus());
        assertEquals("ops-a", second.getReconciliation().getReconciledBy());
        assertEquals("trace-d1", second.getReconciliation().getTraceId(), "应保留首次回写的追踪标识");
    }

    @Test
    @DisplayName("区分拒绝：键不存在 / 键已过期 / 非待对账状态 三种拒绝各不相同")
    void distinctRejectionsForMissingExpiredAndNotPending() {
        // 1) 不存在
        ReconciliationRejectedException notFound = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("c", "k-absent", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                        "ops", "basis", "trace-nf"));
        assertEquals(ReconciliationRejectCode.KEY_NOT_FOUND, notFound.getRejectCode());
        assertEquals(404, notFound.getRejectCode().httpStatus());

        // 2) 已过期：UNCERTAIN 记录超过 TTL
        makeUncertain("c", "k-expired");
        clock.advance(Duration.ofMinutes(11));
        ReconciliationRejectedException expired = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("c", "k-expired", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                        "ops", "basis", "trace-exp"));
        assertEquals(ReconciliationRejectCode.KEY_EXPIRED, expired.getRejectCode());
        assertEquals(409, expired.getRejectCode().httpStatus());

        // 3) 非待对账：原生 FAILED
        commandService.execute("c", "k-term", "hash-k-term", "t", Order.class,
                () -> BusinessTerminal.failed("INVALID_AMOUNT"));
        ReconciliationRejectedException notPending = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("c", "k-term", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                        "ops", "basis", "trace-np"));
        assertEquals(ReconciliationRejectCode.NOT_PENDING, notPending.getRejectCode());

        System.out.println("[输入] 不存在键 / 过期键 / 原生终态键分别回写；[判定依据] 记录存在性、TTL、状态机；"
                + "[结论] KEY_NOT_FOUND(404) / KEY_EXPIRED(409) / NOT_PENDING(409) 三者可区分");
        assertNotEquals(notFound.getRejectCode(), expired.getRejectCode());
        assertNotEquals(expired.getRejectCode(), notPending.getRejectCode());
    }

    @Test
    @DisplayName("IN_FLIGHT 记录回写：拒绝 NOT_PENDING（执行中不可对账）")
    void inFlightRecordCannotBeReconciled() {
        store.claim(IdempotencyRecord.inFlight("c", "k-inflight", "h", clock.instant()),
                properties.getTtl());
        ReconciliationRejectedException ex = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("c", "k-inflight", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                        "ops", "basis", "trace-if"));
        System.out.println("[输入] 对 IN_FLIGHT 占位记录回写；[判定依据] 状态非 UNCERTAIN；[结论] NOT_PENDING");
        assertEquals(ReconciliationRejectCode.NOT_PENDING, ex.getRejectCode());
    }

    @Test
    @DisplayName("遗留未知态记录（改动前结构，无回写字段）：可正常回写与重放")
    void legacyUncertainRecordCanBeReconciledAndReplayed() {
        // 模拟本次改动前产生的记录：直接构造 UNCERTAIN 记录，reconciliation 字段天然为 null
        IdempotencyRecord legacy = IdempotencyRecord.inFlight("c", "k-legacy", "hash-k-legacy", clock.instant());
        store.claim(legacy, properties.getTtl());
        legacy.setStatus(IdempotencyStatus.UNCERTAIN);
        legacy.setErrorCode("DOWNSTREAM_TIMEOUT");
        legacy.setCompletedAt(clock.instant());
        store.complete(legacy, properties.getTtl());

        // 回写前：按旧语义重放 UNCERTAIN，不报错
        IdempotencyOutcome<Order> before = commandService.execute("c", "k-legacy", "hash-k-legacy",
                "t-legacy-replay", Order.class, () -> BusinessTerminal.success(new Order("NO")));
        assertEquals(IdempotencyStatus.UNCERTAIN, before.getStatus());
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, before.getKind());

        // 回写：老记录结构不同（无回写字段）也不得报错
        ReconciliationOutcome outcome = reconciliationService.reconcile(
                "c", "k-legacy", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                "ops-legacy", "manual-ledger-check", "trace-legacy");
        System.out.println("[输入] 改动前遗留的 UNCERTAIN 记录（无回写字段）执行回写；[判定依据] reconciliation=null 视为未对账；"
                + "[状态迁移] UNCERTAIN->SUCCEEDED，可正常重放");
        assertTrue(outcome.isApplied());

        IdempotencyOutcome<Order> after = commandService.execute("c", "k-legacy", "hash-k-legacy",
                "t-legacy-after", Order.class, () -> BusinessTerminal.failed("NO"));
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, after.getKind());
        assertEquals(IdempotencyStatus.SUCCEEDED, after.getStatus());
    }

    @Test
    @DisplayName("积压到上限：新命令被明确拒绝 BACKLOG_FULL；重放与回写不受影响，回写后恢复受理")
    void backlogFullDegradesNewCommandsOnly() {
        properties.setMaxPendingReconciliation(1);
        makeUncertain("c", "k-pending-1");
        assertEquals(1, reconciliationService.pendingBacklog());

        // 新命令被拒绝（明确错误码，不静默丢弃）
        AtomicInteger sideEffects = new AtomicInteger();
        IdempotencyRejectedException ex = assertThrows(IdempotencyRejectedException.class,
                () -> commandService.execute("c", "k-new", "hash-k-new", "trace-new", Order.class, () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("X"));
                }));
        System.out.println("[输入] 待对账积压 1/1 时发起全新命令；[判定依据] pendingReconciliationCount >= max；"
                + "[结论] PENDING_RECONCILIATION_BACKLOG_FULL(503)，fail-closed 无副作用");
        assertEquals(IdempotencyRejectCode.PENDING_RECONCILIATION_BACKLOG_FULL, ex.getRejectCode());
        assertEquals(503, ex.getRejectCode().httpStatus());
        assertEquals(0, sideEffects.get(), "积压降级不得产生副作用");

        // 既有记录重放不受积压限制
        IdempotencyOutcome<Order> replay = commandService.execute("c", "k-pending-1", "hash-k-pending-1",
                "trace-replay", Order.class, () -> BusinessTerminal.success(new Order("NO")));
        assertEquals(IdempotencyStatus.UNCERTAIN, replay.getStatus());
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, replay.getKind());

        // 回写不受积压限制，且回写后积压下降、恢复受理
        ReconciliationOutcome rec = reconciliationService.reconcile(
                "c", "k-pending-1", ReconciliationVerdict.CONFIRMED_FAILED, "ops", "basis", "trace-rec");
        assertTrue(rec.isApplied());
        assertEquals(0, reconciliationService.pendingBacklog());

        IdempotencyOutcome<Order> after = commandService.execute("c", "k-new", "hash-k-new",
                "trace-new-2", Order.class, () -> {
            sideEffects.incrementAndGet();
            return BusinessTerminal.success(new Order("AFTER-DRAIN"));
        });
        System.out.println("[输入] 回写清空积压后再次发起新命令；[判定依据] 积压 0/1 低于上限；[状态] 正常执行");
        assertEquals(IdempotencyStatus.SUCCEEDED, after.getStatus());
        assertEquals(1, sideEffects.get());
    }

    @Test
    @DisplayName("积压观测口径：UNCERTAIN 未回写计入，回写后/原生终态不计入")
    void backlogCountReflectsOnlyPendingReconciliation() {
        assertEquals(0, reconciliationService.pendingBacklog());
        makeUncertain("c", "k-b1");
        makeUncertain("c", "k-b2");
        commandService.execute("c", "k-b3", "hash-k-b3", "t", Order.class,
                () -> BusinessTerminal.success(new Order("OK")));
        assertEquals(2, reconciliationService.pendingBacklog(), "只有 UNCERTAIN 未回写计入积压");
        assertEquals(100, reconciliationService.maxPendingBacklog());

        reconciliationService.reconcile("c", "k-b1", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                "ops", "basis", "trace-b1");
        System.out.println("[输入] 两条 UNCERTAIN + 一条 SUCCEEDED；[判定依据] 观测口径=UNCERTAIN 且未回写；"
                + "[状态] 积压 2，回写一条后降为 1");
        assertEquals(1, reconciliationService.pendingBacklog());
    }
}
