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
 * 对账回写单元测试。
 * 断言稳定可复现；测试名与日志体现输入、判定依据与状态迁移。
 */
class ReconciliationServiceTest {

    public record Order(String id) {
    }

    private MutableClock clock;
    private InMemoryIdempotencyStore store;
    private IdempotencyProperties properties;
    private IdempotentCommandService commandService;
    private ReconciliationService reconciliationService;

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

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        store = new InMemoryIdempotencyStore(clock);
        properties = new IdempotencyProperties();
        properties.setTtl(Duration.ofMinutes(10));
        properties.setInFlightStaleTimeout(Duration.ofMinutes(1));
        properties.setReconciliationMaxPending(100);
        commandService = new IdempotentCommandService(store, properties, new ObjectMapper(), clock);
        reconciliationService = new ReconciliationService(store, properties, clock);
    }

    /** 让指定键以 UNCERTAIN（下游超时）终态落库，返回已发生的副作用计数器。 */
    private AtomicInteger executeUncertain(String caller, String key) {
        AtomicInteger sideEffects = new AtomicInteger();
        IdempotencyOutcome<Order> o = commandService.execute(caller, key, "h-" + key, "t-exec-" + key,
                Order.class, () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.uncertain("DOWNSTREAM_TIMEOUT");
                });
        assertEquals(IdempotencyStatus.UNCERTAIN, o.getStatus());
        return sideEffects;
    }

    @Test
    @DisplayName("回写确认成功：未知态变稳定终态，之后重复提交直接重放该结论，不再回未知")
    void reconcileToSucceededThenReplayStable() {
        AtomicInteger sideEffects = executeUncertain("caller-A", "rec-key-1");

        ReconciliationOutcome outcome = reconciliationService.reconcile(
                "caller-A", "rec-key-1", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                "ops-zhang", "ledger statement #881 confirmed", "trace-rec-1");

        System.out.println("[输入] UNCERTAIN 记录回写 CONFIRMED_SUCCEEDED；[判定依据] 记录处于待对账；"
                + "[状态迁移] UNCERTAIN->SUCCEEDED，reconciliation 元数据落库");
        assertEquals(ReconciliationOutcome.Kind.APPLIED, outcome.getKind());
        assertEquals(IdempotencyStatus.SUCCEEDED, outcome.getStatus());
        assertEquals("ops-zhang", outcome.getEffectiveInfo().getReconciledBy());
        assertEquals("trace-rec-1", outcome.getEffectiveInfo().getTraceId());
        assertEquals(clock.instant(), outcome.getEffectiveInfo().getReconciledAt());

        // 回写后重复提交：直接返回稳定终态并标成重放，不再回未知，也不再执行业务
        IdempotencyOutcome<Order> replay = commandService.execute(
                "caller-A", "rec-key-1", "h-rec-key-1", "t-replay", Order.class, () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("SHOULD-NOT-HAPPEN"));
                });
        System.out.println("[输入] 回写后同键重复提交；[判定依据] 命中已回写的 SUCCEEDED 终态；"
                + "[状态] REPLAYED/SUCCEEDED，业务不执行");
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, replay.getKind());
        assertEquals(IdempotencyStatus.SUCCEEDED, replay.getStatus());
        assertEquals(1, sideEffects.get(), "副作用仍只允许发生一次");
    }

    @Test
    @DisplayName("回写确认未成功：记录迁移为 FAILED 稳定终态并可重放")
    void reconcileToFailedThenReplayStable() {
        executeUncertain("caller-A", "rec-key-2");

        ReconciliationOutcome outcome = reconciliationService.reconcile(
                "caller-A", "rec-key-2", ReconciliationVerdict.CONFIRMED_FAILED,
                "ops-li", "downstream confirmed no debit", "trace-rec-2");

        System.out.println("[输入] UNCERTAIN 记录回写 CONFIRMED_FAILED；[判定依据] 待对账；"
                + "[状态迁移] UNCERTAIN->FAILED");
        assertEquals(ReconciliationOutcome.Kind.APPLIED, outcome.getKind());
        assertEquals(IdempotencyStatus.FAILED, outcome.getStatus());

        IdempotencyOutcome<Order> replay = commandService.execute(
                "caller-A", "rec-key-2", "h-rec-key-2", "t-replay2", Order.class,
                () -> BusinessTerminal.success(new Order("NO")));
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, replay.getKind());
        assertEquals(IdempotencyStatus.FAILED, replay.getStatus());
        assertEquals("RECONCILED_CONFIRMED_FAILED", replay.getErrorCode());
    }

    @Test
    @DisplayName("并发同结论回写：只有一个真正生效，其余幂等拿到已生效结论")
    void concurrentSameVerdictOnlyOneApplies() throws Exception {
        executeUncertain("caller-A", "rec-conc");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger already = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        ReconciliationOutcome o = reconciliationService.reconcile(
                                "caller-A", "rec-conc", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                                "ops-" + idx, "concurrent reconcile", "trace-conc-" + idx);
                        if (o.getKind() == ReconciliationOutcome.Kind.APPLIED) {
                            applied.incrementAndGet();
                        } else {
                            already.incrementAndGet();
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

        System.out.println("[输入] " + threads + " 线程并发同结论回写同一键；[判定依据] store.reconcile 原子判定；"
                + "[状态迁移] 恰好 1 个 APPLIED，其余 ALREADY_RECONCILED");
        assertEquals(1, applied.get(), "并发回写只能有一个真正生效");
        assertEquals(threads - 1, already.get());

        IdempotencyOutcome<Order> replay = commandService.execute(
                "caller-A", "rec-conc", "h-rec-conc", "t-after", Order.class,
                () -> BusinessTerminal.uncertain("X"));
        assertEquals(IdempotencyStatus.SUCCEEDED, replay.getStatus());
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, replay.getKind());
    }

    @Test
    @DisplayName("并发矛盾结论回写：只有一个生效，落败方被拒绝且能看到胜出方")
    void concurrentConflictingVerdictsSingleWinner() throws Exception {
        executeUncertain("caller-A", "rec-conflict");

        int threads = 16; // 一半确认成功、一半确认失败
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<ReconciliationRejectedException> conflictErrors =
                java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                ReconciliationVerdict verdict = idx % 2 == 0
                        ? ReconciliationVerdict.CONFIRMED_SUCCEEDED : ReconciliationVerdict.CONFIRMED_FAILED;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        ReconciliationOutcome o = reconciliationService.reconcile(
                                "caller-A", "rec-conflict", verdict,
                                "ops-" + idx, "conflicting reconcile", "trace-cf-" + idx);
                        if (o.getKind() == ReconciliationOutcome.Kind.APPLIED) {
                            applied.incrementAndGet();
                        }
                    } catch (ReconciliationRejectedException e) {
                        if (e.getRejectCode() == IdempotencyRejectCode.RECONCILE_CONFLICT) {
                            conflicts.incrementAndGet();
                            conflictErrors.add(e);
                        } else {
                            throw e;
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

        System.out.println("[输入] " + threads + " 线程并发矛盾结论回写；[判定依据] 原子判定只有一个 APPLIED；"
                + "[结论] 矛盾落败方得到 RECONCILE_CONFLICT 且携带胜出方信息");
        assertEquals(1, applied.get(), "矛盾结论不能两个都写进去");
        assertTrue(conflicts.get() > 0, "与胜出结论矛盾的请求必须被明确拒绝");

        // 最终记录状态 = 胜出结论；每个落败方都能看出被谁抢先
        var finalRecord = store.findIncludingExpired("caller-A", "rec-conflict").orElseThrow();
        var winnerInfo = finalRecord.getReconciliation();
        assertNotNull(winnerInfo);
        assertEquals(winnerInfo.getVerdict().targetStatus(), finalRecord.getStatus());
        for (ReconciliationRejectedException e : conflictErrors) {
            assertNotNull(e.getWinner(), "落败方必须能看到胜出方");
            assertEquals(winnerInfo.getReconciledBy(), e.getWinner().getReconciledBy());
            assertEquals(winnerInfo.getTraceId(), e.getWinner().getTraceId());
            assertNotEquals(e.getMessage().contains(winnerInfo.getReconciledBy()), false);
        }
    }

    @Test
    @DisplayName("顺序矛盾回写：第二次矛盾结论被拒绝，胜者信息可见，记录不被改写")
    void sequentialConflictingVerdictRejected() {
        executeUncertain("caller-A", "rec-seq");
        reconciliationService.reconcile("caller-A", "rec-seq", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                "ops-winner", "statement confirmed", "trace-winner");

        ReconciliationRejectedException ex = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("caller-A", "rec-seq",
                        ReconciliationVerdict.CONFIRMED_FAILED, "ops-loser", "dispute", "trace-loser"));

        System.out.println("[输入] 已回写 SUCCEEDED 后再回写 CONFIRMED_FAILED；[判定依据] 已生效结论矛盾；"
                + "[结论] RECONCILE_CONFLICT，胜者 ops-winner 可见，记录保持 SUCCEEDED");
        assertEquals(IdempotencyRejectCode.RECONCILE_CONFLICT, ex.getRejectCode());
        assertEquals("ops-winner", ex.getWinner().getReconciledBy());
        assertEquals("trace-winner", ex.getWinner().getTraceId());
        assertEquals(ReconciliationVerdict.CONFIRMED_SUCCEEDED, ex.getWinner().getVerdict());

        var record = store.findIncludingExpired("caller-A", "rec-seq").orElseThrow();
        assertEquals(IdempotencyStatus.SUCCEEDED, record.getStatus(), "落败结论不得改写记录");
        assertEquals("ops-winner", record.getReconciliation().getReconciledBy());
    }

    @Test
    @DisplayName("重复同结论回写：幂等返回已生效结论（applied=false）")
    void duplicateSameVerdictIsIdempotent() {
        executeUncertain("caller-A", "rec-dup");
        reconciliationService.reconcile("caller-A", "rec-dup", ReconciliationVerdict.CONFIRMED_FAILED,
                "ops-first", "no debit", "trace-first");

        ReconciliationOutcome second = reconciliationService.reconcile(
                "caller-A", "rec-dup", ReconciliationVerdict.CONFIRMED_FAILED,
                "ops-second", "no debit (recheck)", "trace-second");

        System.out.println("[输入] 同结论重复回写；[判定依据] 已生效结论一致；[结论] ALREADY_RECONCILED，"
                + "生效元数据仍是首次回写者");
        assertEquals(ReconciliationOutcome.Kind.ALREADY_RECONCILED, second.getKind());
        assertEquals(IdempotencyStatus.FAILED, second.getStatus());
        assertEquals("ops-first", second.getEffectiveInfo().getReconciledBy());
        assertEquals("trace-first", second.getEffectiveInfo().getTraceId());
    }

    @Test
    @DisplayName("区分拒绝：键不存在 / 键已过期 / 不在待对账状态 各得专属错误码")
    void distinctRejectionsForNotFoundExpiredNotPending() {
        // 1) 键不存在
        ReconciliationRejectedException notFound = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("caller-A", "missing-key",
                        ReconciliationVerdict.CONFIRMED_SUCCEEDED, "ops", "r", "t1"));
        System.out.println("[输入] 回写不存在的键；[判定依据] 存储无记录；[结论] RECONCILE_KEY_NOT_FOUND/404");
        assertEquals(IdempotencyRejectCode.RECONCILE_KEY_NOT_FOUND, notFound.getRejectCode());
        assertEquals(404, notFound.getRejectCode().httpStatus());

        // 2) 键已过期（UNCERTAIN 落库后时钟越过 TTL）
        executeUncertain("caller-A", "rec-expired");
        clock.advance(Duration.ofMinutes(11));
        ReconciliationRejectedException expired = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("caller-A", "rec-expired",
                        ReconciliationVerdict.CONFIRMED_SUCCEEDED, "ops", "r", "t2"));
        System.out.println("[输入] 回写已过期的键；[判定依据] completedAt+ttl < now；[结论] RECONCILE_KEY_EXPIRED/410");
        assertEquals(IdempotencyRejectCode.RECONCILE_KEY_EXPIRED, expired.getRejectCode());
        assertEquals(410, expired.getRejectCode().httpStatus());

        // 3a) 已成功终态：不在待对账
        commandService.execute("caller-A", "rec-succ", "h-rec-succ", "t3", Order.class,
                () -> BusinessTerminal.success(new Order("OK")));
        ReconciliationRejectedException notPendingSucc = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("caller-A", "rec-succ",
                        ReconciliationVerdict.CONFIRMED_FAILED, "ops", "r", "t4"));
        System.out.println("[输入] 回写 SUCCEEDED 记录；[判定依据] 非待对账；[结论] RECONCILE_NOT_PENDING/409");
        assertEquals(IdempotencyRejectCode.RECONCILE_NOT_PENDING, notPendingSucc.getRejectCode());
        assertEquals(409, notPendingSucc.getRejectCode().httpStatus());

        // 3b) 已失败终态：不在待对账
        commandService.execute("caller-A", "rec-fail", "h-rec-fail", "t5", Order.class,
                () -> BusinessTerminal.failed("INVALID_AMOUNT"));
        ReconciliationRejectedException notPendingFail = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("caller-A", "rec-fail",
                        ReconciliationVerdict.CONFIRMED_SUCCEEDED, "ops", "r", "t6"));
        assertEquals(IdempotencyRejectCode.RECONCILE_NOT_PENDING, notPendingFail.getRejectCode());

        // 3c) 仍在执行：不在待对账
        store.claim(IdempotencyRecord.inFlight("caller-A", "rec-inflight", "h", clock.instant()),
                properties.getTtl());
        ReconciliationRejectedException notPendingInflight = assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("caller-A", "rec-inflight",
                        ReconciliationVerdict.CONFIRMED_SUCCEEDED, "ops", "r", "t7"));
        System.out.println("[输入] 回写 IN_FLIGHT 记录；[判定依据] 非待对账；[结论] RECONCILE_NOT_PENDING/409");
        assertEquals(IdempotencyRejectCode.RECONCILE_NOT_PENDING, notPendingInflight.getRejectCode());
    }

    @Test
    @DisplayName("已成功/已失败的记录不允许被回写改掉")
    void terminalRecordsAreImmutable() {
        commandService.execute("caller-A", "imm-succ", "h-imm-succ", "t", Order.class,
                () -> BusinessTerminal.success(new Order("KEEP")));
        assertThrows(ReconciliationRejectedException.class,
                () -> reconciliationService.reconcile("caller-A", "imm-succ",
                        ReconciliationVerdict.CONFIRMED_FAILED, "ops", "try override", "t2"));

        IdempotencyOutcome<Order> replay = commandService.execute(
                "caller-A", "imm-succ", "h-imm-succ", "t3", Order.class,
                () -> BusinessTerminal.failed("X"));
        System.out.println("[输入] 对 SUCCEEDED 记录回写失败后重放；[判定依据] 回写被拒绝；[状态] 仍为 SUCCEEDED");
        assertEquals(IdempotencyStatus.SUCCEEDED, replay.getStatus());
        assertEquals("KEEP", replay.getResult().id());
    }

    @Test
    @DisplayName("遗留未知态记录（无回写元数据的老结构）：可正常回写与重放")
    void legacyUncertainRecordCanBeReconciledAndReplayed() {
        // 模拟本次改动前产生的老记录：直接构造 UNCERTAIN 记录，无 reconciliation 字段、completedAt 为 null
        IdempotencyRecord legacy = new IdempotencyRecord(
                "caller-A", "legacy-key", "h-legacy", IdempotencyStatus.UNCERTAIN, clock.instant());
        legacy.setErrorCode("DOWNSTREAM_TIMEOUT");
        store.claim(legacy, properties.getTtl());

        // 老记录重放仍是未知（不报错、不当成全新请求）
        IdempotencyOutcome<Order> before = commandService.execute(
                "caller-A", "legacy-key", "h-legacy", "t-legacy-replay", Order.class,
                () -> BusinessTerminal.success(new Order("NO")));
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, before.getKind());
        assertEquals(IdempotencyStatus.UNCERTAIN, before.getStatus());

        ReconciliationOutcome outcome = reconciliationService.reconcile(
                "caller-A", "legacy-key", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                "ops-legacy", "backfilled from old statement", "trace-legacy");

        System.out.println("[输入] 遗留 UNCERTAIN 记录回写；[判定依据] reconciliation=null 视为待对账；"
                + "[状态迁移] UNCERTAIN->SUCCEEDED，老记录不报错");
        assertEquals(ReconciliationOutcome.Kind.APPLIED, outcome.getKind());

        IdempotencyOutcome<Order> after = commandService.execute(
                "caller-A", "legacy-key", "h-legacy", "t-legacy-after", Order.class,
                () -> BusinessTerminal.uncertain("X"));
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, after.getKind());
        assertEquals(IdempotencyStatus.SUCCEEDED, after.getStatus());
    }

    @Test
    @DisplayName("积压到上限：新键降级拒绝 RECONCILE_BACKLOG_FULL，已有键重放/回写不受影响，消化后恢复")
    void backlogLimitDegradesNewKeys() {
        properties.setReconciliationMaxPending(1);
        executeUncertain("caller-A", "backlog-1");

        var snapshot = reconciliationService.backlogSnapshot();
        System.out.println("[观测] pending=" + snapshot.pending() + " maxPending=" + snapshot.maxPending());
        assertEquals(1, snapshot.pending());
        assertEquals(1, snapshot.maxPending());

        // 到上限：全新键被明确拒绝（非静默丢弃），业务不执行
        AtomicInteger sideEffects = new AtomicInteger();
        IdempotencyRejectedException ex = assertThrows(IdempotencyRejectedException.class,
                () -> commandService.execute("caller-A", "backlog-new", "h-backlog-new", "t-new",
                        Order.class, () -> {
                            sideEffects.incrementAndGet();
                            return BusinessTerminal.success(new Order("X"));
                        }));
        System.out.println("[输入] 积压 1/1 时提交新键；[判定依据] pending >= maxPending；"
                + "[结论] RECONCILE_BACKLOG_FULL/503，无副作用、无新记录");
        assertEquals(IdempotencyRejectCode.RECONCILE_BACKLOG_FULL, ex.getRejectCode());
        assertEquals(503, ex.getRejectCode().httpStatus());
        assertEquals(0, sideEffects.get());
        assertTrue(store.findIncludingExpired("caller-A", "backlog-new").isEmpty(),
                "被拒绝的新键不得留下记录（内存不增长）");

        // 已有键的重放不受积压影响
        IdempotencyOutcome<Order> replay = commandService.execute(
                "caller-A", "backlog-1", "h-backlog-1", "t-replay", Order.class,
                () -> BusinessTerminal.success(new Order("NO")));
        assertEquals(IdempotencyOutcome.Kind.REPLAYED, replay.getKind());
        assertEquals(IdempotencyStatus.UNCERTAIN, replay.getStatus());

        // 回写消化积压后，新键恢复受理
        reconciliationService.reconcile("caller-A", "backlog-1", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                "ops", "cleared", "t-clear");
        assertEquals(0, reconciliationService.backlogSnapshot().pending());

        IdempotencyOutcome<Order> accepted = commandService.execute(
                "caller-A", "backlog-new", "h-backlog-new", "t-new-2", Order.class, () -> {
                    sideEffects.incrementAndGet();
                    return BusinessTerminal.success(new Order("ACCEPTED"));
                });
        System.out.println("[输入] 积压消化后再次提交新键；[判定依据] pending < maxPending；[状态] 正常执行");
        assertEquals(IdempotencyOutcome.Kind.EXECUTED, accepted.getKind());
        assertEquals(1, sideEffects.get());
    }

    @Test
    @DisplayName("积压观测口径：过期待对账记录默认不计入，配置 includeExpired 后计入并被惰性清理")
    void backlogObservabilityIncludeExpired() {
        executeUncertain("caller-A", "obs-1");
        assertEquals(1, reconciliationService.backlogSnapshot().pending());

        clock.advance(Duration.ofMinutes(11)); // 越过 TTL

        // 默认口径：已过期不计入，且观测时被惰性清理
        var snapshot = reconciliationService.backlogSnapshot();
        System.out.println("[观测] 过期后默认口径 pending=" + snapshot.pending()
                + " includeExpired=" + snapshot.includeExpired());
        assertEquals(0, snapshot.pending());
        assertFalse(snapshot.includeExpired());

        // 调整口径：包含已过期未清理记录（重新制造一条未清理的过期记录）
        executeUncertainFreshAfterExpiry();
        properties.setReconciliationBacklogIncludeExpired(true);
        var widened = reconciliationService.backlogSnapshot();
        System.out.println("[观测] includeExpired=true 口径 pending=" + widened.pending());
        assertEquals(1, widened.pending());
        assertTrue(widened.includeExpired());
    }

    private void executeUncertainFreshAfterExpiry() {
        // 上一段观测已惰性清理过期记录，可直接用新键制造一条过期待对账记录
        commandService.execute("caller-A", "obs-2", "h-obs-2", "t-obs2", Order.class,
                () -> BusinessTerminal.uncertain("DOWNSTREAM_TIMEOUT"));
        clock.advance(Duration.ofMinutes(11));
    }

    @Test
    @DisplayName("回写元数据缺省校验：操作者与依据必填")
    void reconcileRequiresOperatorAndReason() {
        executeUncertain("caller-A", "rec-validate");
        assertThrows(IllegalArgumentException.class, () -> reconciliationService.reconcile(
                "caller-A", "rec-validate", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                " ", "reason", "t"));
        assertThrows(IllegalArgumentException.class, () -> reconciliationService.reconcile(
                "caller-A", "rec-validate", ReconciliationVerdict.CONFIRMED_SUCCEEDED,
                "ops", null, "t"));
        System.out.println("[输入] reconciledBy/reason 缺失；[判定依据] 入参校验；[结论] 400 级参数错误");
    }
}
