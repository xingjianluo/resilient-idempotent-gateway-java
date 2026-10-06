package com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyProperties;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRecord;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyRejectCode;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.IdempotencyStore;
import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.StoreAccessException;
import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 对账回写入口：把“结果未知、尚未对账”的 UNCERTAIN 记录补写为权威稳定终态。
 * <p>
 * 规则（详见 README）：
 * <ul>
 *     <li>仅 UNCERTAIN 且未回写过的记录可被回写；SUCCEEDED/FAILED/IN_FLIGHT 一律拒绝；</li>
 *     <li>回写原子生效：并发回写只有一个真正生效，同结论的落败方幂等拿到已生效结论，
 *         矛盾结论的落败方被明确拒绝并能看到胜出方；</li>
 *     <li>键不存在 / 键已过期 / 不在待对账状态分别返回可区分的拒绝码；</li>
 *     <li>回写元数据（谁、何时、依据、结论、traceId）随记录保存，日志与响应不含凭据。</li>
 * </ul>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ReconciliationService(IdempotencyStore store, IdempotencyProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    public ReconciliationService(IdempotencyStore store, IdempotencyProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 对指定键执行对账回写。
     */
    public ReconciliationOutcome reconcile(String callerId, String key, ReconciliationVerdict verdict,
                                           String reconciledBy, String reason, String traceId) {
        validate(callerId, key, verdict, reconciledBy, reason);

        ReconciliationInfo info = new ReconciliationInfo(
                reconciledBy, clock.instant(), reason, traceId, verdict);

        ReconcileStoreResult result;
        try {
            result = store.reconcile(callerId, key, properties.getTtl(), info);
        } catch (StoreAccessException e) {
            log.warn("[{}] store unavailable on reconcile, reject. callerId={} key={}",
                    traceId, callerId, key);
            throw new ReconciliationRejectedException(IdempotencyRejectCode.STORE_UNAVAILABLE,
                    "idempotency store is temporarily unavailable, reconciliation rejected", traceId);
        }

        switch (result.status()) {
            case APPLIED -> {
                log.info("[{}] reconciliation applied: UNCERTAIN -> {} callerId={} key={} by={} reason={}",
                        traceId, verdict.targetStatus(), callerId, key, reconciledBy,
                        SensitiveDataSanitizer.sanitizeJson(reason));
                return ReconciliationOutcome.applied(info, traceId);
            }
            case NOT_FOUND -> {
                log.info("[{}] reconciliation rejected: key not found. callerId={} key={}",
                        traceId, callerId, key);
                throw new ReconciliationRejectedException(IdempotencyRejectCode.RECONCILE_KEY_NOT_FOUND,
                        "no idempotency record found for the given caller and key", traceId);
            }
            case EXPIRED -> {
                log.info("[{}] reconciliation rejected: key expired. callerId={} key={}",
                        traceId, callerId, key);
                throw new ReconciliationRejectedException(IdempotencyRejectCode.RECONCILE_KEY_EXPIRED,
                        "idempotency key has expired, reconciliation is no longer accepted", traceId);
            }
            case NOT_PENDING -> {
                return handleNotPending(result.current(), verdict, callerId, key, traceId);
            }
            default -> throw new IllegalStateException("unexpected reconcile status: " + result.status());
        }
    }

    private ReconciliationOutcome handleNotPending(IdempotencyRecord record, ReconciliationVerdict verdict,
                                                   String callerId, String key, String traceId) {
        ReconciliationInfo winner = record.getReconciliation();
        if (winner != null) {
            // 已被回写过：同结论 -> 幂等返回已生效结论；矛盾结论 -> 明确拒绝并带出胜出方
            if (record.getStatus() == verdict.targetStatus()) {
                log.info("[{}] duplicate reconciliation with same verdict, already effective by={} at={} "
                                + "winnerTraceId={} callerId={} key={}",
                        traceId, winner.getReconciledBy(), winner.getReconciledAt(),
                        winner.getTraceId(), callerId, key);
                return ReconciliationOutcome.alreadyReconciled(winner, traceId);
            }
            log.warn("[{}] reconciliation conflict: verdict={} lost to verdict={} by={} at={} "
                            + "winnerTraceId={} callerId={} key={}",
                    traceId, verdict, winner.getVerdict(), winner.getReconciledBy(),
                    winner.getReconciledAt(), winner.getTraceId(), callerId, key);
            throw new ReconciliationRejectedException(IdempotencyRejectCode.RECONCILE_CONFLICT,
                    "conflicting reconciliation verdict; key was already reconciled to "
                            + winner.getVerdict().targetStatus() + " by " + winner.getReconciledBy()
                            + " at " + winner.getReconciledAt(),
                    traceId, winner);
        }
        // 从未处于待对账：自然终态（SUCCEEDED/FAILED）或仍在执行
        log.info("[{}] reconciliation rejected: record not pending, status={} callerId={} key={}",
                traceId, record.getStatus(), callerId, key);
        throw new ReconciliationRejectedException(IdempotencyRejectCode.RECONCILE_NOT_PENDING,
                "record is not pending reconciliation (current status: " + record.getStatus() + ")",
                traceId);
    }

    /**
     * 当前待对账积压量的观测快照。
     */
    public BacklogSnapshot backlogSnapshot() {
        long pending;
        try {
            pending = store.pendingReconciliationCount(properties.getTtl(),
                    properties.isReconciliationBacklogIncludeExpired());
        } catch (StoreAccessException e) {
            log.warn("store unavailable on backlog snapshot");
            throw new ReconciliationRejectedException(IdempotencyRejectCode.STORE_UNAVAILABLE,
                    "idempotency store is temporarily unavailable, backlog snapshot rejected",
                    null);
        }
        return new BacklogSnapshot(pending, properties.getReconciliationMaxPending(),
                properties.isReconciliationBacklogIncludeExpired());
    }

    private void validate(String callerId, String key, ReconciliationVerdict verdict,
                          String reconciledBy, String reason) {
        if (callerId == null || callerId.isBlank()) {
            throw new IllegalArgumentException("X-Caller-Id is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (verdict == null) {
            throw new IllegalArgumentException("verdict is required (CONFIRMED_SUCCEEDED or CONFIRMED_FAILED)");
        }
        if (reconciledBy == null || reconciledBy.isBlank()) {
            throw new IllegalArgumentException("reconciledBy is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
    }

    /**
     * 待对账积压观测快照。
     *
     * @param pending       当前待对账（UNCERTAIN 且未回写、未过期）记录数
     * @param maxPending    配置的积压上限
     * @param includeExpired 观测口径是否包含已过期但尚未清理的记录
     */
    public record BacklogSnapshot(long pending, long maxPending, boolean includeExpired) {
    }
}
