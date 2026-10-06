package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRecord;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStatus;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.ReconciliationInfo;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.StoreAccessException;
import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 对账回写入口：把“结果未知、尚未对账”（UNCERTAIN 且未回写）的记录收敛为权威终态。
 * <p>
 * 规则：
 * <ul>
 *     <li>仅 UNCERTAIN 且未对账的记录可回写；回写后成为稳定终态，之后同键提交直接重放该结论；</li>
 *     <li>原生 SUCCEEDED / FAILED 终态不受影响，回写一律拒绝（NOT_PENDING）；</li>
 *     <li>并发 / 重复回写由存储层原子判定，恰好一个生效；结论相同返回已生效结论，
 *         结论冲突落败方得到 VERDICT_CONFLICT 并能看出抢先者；</li>
 *     <li>键不存在 / 键已过期 / 非待对账状态分别拒绝，错误码可区分；</li>
 *     <li>回写记录“谁、何时、依据什么”，与 traceId 串联；日志不含凭据类内容。</li>
 * </ul>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final Clock clock;

    @Autowired
    public ReconciliationService(IdempotencyStore store, IdempotencyProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    public ReconciliationService(IdempotencyStore store, IdempotencyProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 对 (callerId, key) 的待对账记录回写权威结论。
     *
     * @param verdict      权威结论：确认已成功 / 确认未成功
     * @param reconciledBy 回写人（操作者标识）
     * @param basis        回写依据（如对账单号），日志中脱敏
     * @param traceId      追踪标识
     */
    public ReconciliationOutcome reconcile(String callerId, String key, ReconciliationVerdict verdict,
                                           String reconciledBy, String basis, String traceId) {
        validate(callerId, key, verdict, reconciledBy, basis);
        String safeBasis = SensitiveDataSanitizer.sanitizeJson(basis);
        log.info("[{}] reconciliation requested callerId={} key={} verdict={} by={} basis={}",
                traceId, callerId, key, verdict, reconciledBy, safeBasis);

        // 1) 读取记录（含已过期）；存储故障 -> 拒绝，不产生任何回写副作用
        Optional<IdempotencyRecord> existing = readRecord(callerId, key, traceId);
        if (existing.isEmpty()) {
            log.info("[{}] reconciliation rejected: key not found. callerId={} key={}",
                    traceId, callerId, key);
            throw new ReconciliationRejectedException(ReconciliationRejectCode.KEY_NOT_FOUND,
                    "no idempotency record found for this key", traceId);
        }
        IdempotencyRecord record = existing.get();

        // 2) 终态过期（IN_FLIGHT 不受 TTL 约束，由幂等入口的卡死判定处理）
        if (record.getStatus() != IdempotencyStatus.IN_FLIGHT && isExpired(record)) {
            log.info("[{}] reconciliation rejected: key expired. key={} completedAt={}",
                    traceId, key, record.getCompletedAt());
            throw new ReconciliationRejectedException(ReconciliationRejectCode.KEY_EXPIRED,
                    "idempotency key has expired, reconciliation is no longer accepted", traceId);
        }

        // 3) 已被回写过：同结论返回已生效结论（重复回写安全），异结论判定胜负
        if (record.getReconciliation() != null) {
            return resolveAlreadyReconciled(record, verdict, traceId);
        }

        // 4) 未回写但不在待对账状态：IN_FLIGHT / 原生 SUCCEEDED / FAILED 一律拒绝
        if (record.getStatus() != IdempotencyStatus.UNCERTAIN) {
            log.info("[{}] reconciliation rejected: record not pending. key={} status={}",
                    traceId, key, record.getStatus());
            throw new ReconciliationRejectedException(ReconciliationRejectCode.NOT_PENDING,
                    "record is not pending reconciliation (current status: " + record.getStatus() + ")",
                    traceId);
        }

        // 5) 原子回写：并发下恰好一个生效
        ReconciliationInfo info = new ReconciliationInfo(
                verdict.terminalStatus(), reconciledBy, clock.instant(), basis, traceId);
        boolean applied;
        try {
            applied = store.reconcileIfPending(callerId, key, info);
        } catch (StoreAccessException e) {
            log.warn("[{}] reconciliation rejected: store unavailable on write. key={}", traceId, key);
            throw new ReconciliationRejectedException(ReconciliationRejectCode.STORE_UNAVAILABLE,
                    "idempotency store is temporarily unavailable, reconciliation rejected (no side effect)",
                    traceId);
        }
        if (applied) {
            log.info("[{}] reconciliation applied: UNCERTAIN -> {}. key={} by={} basis={}",
                    traceId, verdict.terminalStatus(), key, reconciledBy, safeBasis);
            return ReconciliationOutcome.applied(info, traceId);
        }

        // 6) 竞争落败：重读记录，给出已生效结论或明确的冲突结论
        IdempotencyRecord current = readRecord(callerId, key, traceId).orElse(null);
        if (current != null && current.getReconciliation() != null) {
            return resolveAlreadyReconciled(current, verdict, traceId);
        }
        // 记录状态发生了预期外变化（如刚好过期被淘汰），保守拒绝而非猜测
        log.warn("[{}] reconciliation lost race and record no longer pending; rejecting conservatively. key={}",
                traceId, key);
        throw new ReconciliationRejectedException(ReconciliationRejectCode.NOT_PENDING,
                "record is no longer pending reconciliation", traceId);
    }

    /** 当前待对账积压量与上限（可观测口径）。 */
    public long pendingBacklog() {
        return store.pendingReconciliationCount();
    }

    public long maxPendingBacklog() {
        return properties.getMaxPendingReconciliation();
    }

    private ReconciliationOutcome resolveAlreadyReconciled(IdempotencyRecord record,
                                                           ReconciliationVerdict verdict, String traceId) {
        ReconciliationInfo winner = record.getReconciliation();
        if (record.getStatus() == verdict.terminalStatus()) {
            log.info("[{}] reconciliation already applied with same verdict by={} at={} traceId={}; "
                            + "returning effective conclusion. key={}",
                    traceId, winner.getReconciledBy(), winner.getReconciledAt(),
                    winner.getTraceId(), record.getKey());
            return ReconciliationOutcome.alreadyApplied(winner, traceId);
        }
        log.warn("[{}] reconciliation conflict: verdict={} lost to verdict={} by={} at={} winnerTraceId={}. key={}",
                traceId, verdict.terminalStatus(), winner.getVerdictStatus(),
                winner.getReconciledBy(), winner.getReconciledAt(), winner.getTraceId(), record.getKey());
        throw new ReconciliationRejectedException(ReconciliationRejectCode.VERDICT_CONFLICT,
                "conflicting verdict already applied by " + winner.getReconciledBy()
                        + " at " + winner.getReconciledAt()
                        + " (status: " + winner.getVerdictStatus() + ")",
                traceId, winner);
    }

    private Optional<IdempotencyRecord> readRecord(String callerId, String key, String traceId) {
        try {
            return store.findIncludingExpired(callerId, key);
        } catch (StoreAccessException e) {
            log.warn("[{}] reconciliation rejected: store unavailable on read. key={}", traceId, key);
            throw new ReconciliationRejectedException(ReconciliationRejectCode.STORE_UNAVAILABLE,
                    "idempotency store is temporarily unavailable, reconciliation rejected (no side effect)",
                    traceId);
        }
    }

    private boolean isExpired(IdempotencyRecord record) {
        Instant base = record.getCompletedAt() != null ? record.getCompletedAt() : record.getCreatedAt();
        return base.plus(properties.getTtl()).isBefore(clock.instant());
    }

    private void validate(String callerId, String key, ReconciliationVerdict verdict,
                          String reconciledBy, String basis) {
        if (callerId == null || callerId.isBlank()) {
            throw new IllegalArgumentException("X-Caller-Id is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (verdict == null) {
            throw new IllegalArgumentException("verdict is required (CONFIRMED_SUCCEEDED / CONFIRMED_FAILED)");
        }
        if (reconciledBy == null || reconciledBy.isBlank()) {
            throw new IllegalArgumentException("reconciledBy is required");
        }
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException("basis is required");
        }
    }
}
