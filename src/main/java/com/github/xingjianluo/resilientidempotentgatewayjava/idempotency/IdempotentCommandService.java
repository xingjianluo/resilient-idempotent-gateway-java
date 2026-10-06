package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 并发安全的幂等命令入口。
 * <p>
 * 以 {@code (callerId, idempotencyKey)} 为收敛维度，保证：
 * <ul>
 *     <li>副作用只发生一次：并发提交只有一个请求真正执行，其余得到
 *         {@link IdempotencyRejectCode#CONCURRENT_IN_FLIGHT}；</li>
 *     <li>重复提交返回稳定结果：终态（SUCCEEDED/FAILED/UNCERTAIN）被重放，
 *         业务不再执行；</li>
 *     <li>同键不同载荷：{@link IdempotencyRejectCode#PAYLOAD_MISMATCH}；</li>
 *     <li>过期后重放：{@link IdempotencyRejectCode#KEY_EXPIRED}；</li>
 *     <li>存储暂时不可用：fail-closed，{@link IdempotencyRejectCode#STORE_UNAVAILABLE}，
 *         绝不执行业务，避免无法去重的副作用；</li>
 *     <li>下游结果未知时标记 UNCERTAIN，重复请求只重放不确定结论，绝不静默重试。</li>
 * </ul>
 */
@Service
public class IdempotentCommandService {

    private static final Logger log = LoggerFactory.getLogger(IdempotentCommandService.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public IdempotentCommandService(IdempotencyStore store, IdempotencyProperties properties,
                                    ObjectMapper objectMapper) {
        this(store, properties, objectMapper, Clock.systemUTC());
    }

    public IdempotentCommandService(IdempotencyStore store, IdempotencyProperties properties,
                                    ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 执行（或重放）幂等命令。
     *
     * @param callerId    调用方标识
     * @param key         幂等键
     * @param payloadHash 载荷摘要（用于检测同键不同载荷）
     * @param traceId     追踪标识
     * @param resultType  结果类型，用于终态重放反序列化
     * @param business    业务逻辑，返回终态结果
     */
    public <T> IdempotencyOutcome<T> execute(String callerId, String key, String payloadHash,
                                             String traceId, Class<T> resultType,
                                             Supplier<BusinessTerminal<T>> business) {
        validate(callerId, key, payloadHash);

        // 1) 读取既有记录（含已过期）；存储故障 -> fail-closed 降级，不执行业务
        Optional<IdempotencyRecord> existing;
        try {
            existing = store.findIncludingExpired(callerId, key);
        } catch (StoreAccessException e) {
            log.warn("[{}] store unavailable on read, fail-closed reject. callerId={} key={}",
                    traceId, callerId, key);
            throw new IdempotencyRejectedException(IdempotencyRejectCode.STORE_UNAVAILABLE,
                    "idempotency store is temporarily unavailable, request rejected (no side effect)", traceId);
        }

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            return handleExisting(record, payloadHash, traceId, resultType, business);
        }

        // 1.5) 全新键：待对账积压达到上限时降级，拒绝受理新键（不占位、不执行业务）。
        // 已有键的重放/对账回写不受影响；积压随回写或过期清理而下降后自动恢复。
        ensureBacklogBelowLimit(callerId, key, traceId);

        // 2) 无记录：原子占位
        IdempotencyRecord claimed = IdempotencyRecord.inFlight(
                callerId, key, payloadHash, clock.instant());
        boolean acquired;
        try {
            acquired = store.claim(claimed, properties.getTtl());
        } catch (StoreAccessException e) {
            log.warn("[{}] store unavailable on claim, fail-closed reject. callerId={} key={}",
                    traceId, callerId, key);
            throw new IdempotencyRejectedException(IdempotencyRejectCode.STORE_UNAVAILABLE,
                    "idempotency store is temporarily unavailable, request rejected (no side effect)", traceId);
        }

        if (!acquired) {
            // 并发竞争中落败：读取胜出者记录并给出结论
            return handleConcurrentLoser(callerId, key, payloadHash, traceId, resultType, business);
        }

        return runBusiness(claimed, traceId, resultType, business);
    }

    private void ensureBacklogBelowLimit(String callerId, String key, String traceId) {
        long max = properties.getReconciliationMaxPending();
        if (max <= 0) {
            return; // 上限 <=0 表示不启用积压降级
        }
        long pending;
        try {
            pending = store.pendingReconciliationCount(properties.getTtl(),
                    properties.isReconciliationBacklogIncludeExpired());
        } catch (StoreAccessException e) {
            log.warn("[{}] store unavailable on backlog check, fail-closed reject. callerId={} key={}",
                    traceId, callerId, key);
            throw new IdempotencyRejectedException(IdempotencyRejectCode.STORE_UNAVAILABLE,
                    "idempotency store is temporarily unavailable, request rejected (no side effect)", traceId);
        }
        if (pending >= max) {
            log.warn("[{}] reconciliation backlog full ({}/{}), new key rejected. callerId={} key={}",
                    traceId, pending, max, callerId, key);
            throw new IdempotencyRejectedException(IdempotencyRejectCode.RECONCILE_BACKLOG_FULL,
                    "reconciliation backlog is full (" + pending + "/" + max
                            + "), new keys are rejected until pending records are reconciled or expire",
                    traceId);
        }
    }

    private <T> IdempotencyOutcome<T> handleExisting(IdempotencyRecord record, String payloadHash,
                                                     String traceId, Class<T> resultType,
                                                     Supplier<BusinessTerminal<T>> business) {
        // IN_FLIGHT：区分“正常并发”与“持有者崩溃后的卡死占位”
        if (record.getStatus() == IdempotencyStatus.IN_FLIGHT) {
            Instant since = record.getCreatedAt();
            boolean stale = since.plus(properties.getInFlightStaleTimeout()).isBefore(clock.instant());
            if (!stale) {
                log.info("[{}] concurrent duplicate rejected, in-flight holder since {} (payloadHash={})",
                        traceId, since, SensitiveDataSanitizer.mask(payloadHash));
                throw new IdempotencyRejectedException(IdempotencyRejectCode.CONCURRENT_IN_FLIGHT,
                        "an identical request is currently being processed", traceId);
            }
            // 卡死占位：原子接管后执行；接管失败说明已有别的请求接管
            IdempotencyRecord replacement = IdempotencyRecord.inFlight(
                    record.getCallerId(), record.getKey(), payloadHash, clock.instant());
            boolean took;
            try {
                took = store.takeoverStaleInFlight(record, replacement);
            } catch (StoreAccessException e) {
                log.warn("[{}] store unavailable on takeover, fail-closed reject. key={}", traceId,
                        record.getKey());
                throw new IdempotencyRejectedException(IdempotencyRejectCode.STORE_UNAVAILABLE,
                        "idempotency store is temporarily unavailable, request rejected (no side effect)", traceId);
            }
            if (!took) {
                throw new IdempotencyRejectedException(IdempotencyRejectCode.CONCURRENT_IN_FLIGHT,
                        "an identical request is currently being processed", traceId);
            }
            log.warn("[{}] stale IN_FLIGHT record taken over, re-executing. key={}", traceId, record.getKey());
            return runBusiness(replacement, traceId, resultType, business);
        }

        // 终态：先校验 TTL（过期重放必须可区分地拒绝）
        Instant completedAt = record.getCompletedAt() != null ? record.getCompletedAt() : record.getCreatedAt();
        if (completedAt.plus(properties.getTtl()).isBefore(clock.instant())) {
            log.info("[{}] expired idempotency key replay rejected. key={} completedAt={}",
                    traceId, record.getKey(), completedAt);
            throw new IdempotencyRejectedException(IdempotencyRejectCode.KEY_EXPIRED,
                    "idempotency key has expired, please use a new key", traceId);
        }

        // 同键不同载荷
        if (!record.getPayloadHash().equals(payloadHash)) {
            log.warn("[{}] payload mismatch for same idempotency key. key={} storedHash={} incomingHash={}",
                    traceId, record.getKey(),
                    SensitiveDataSanitizer.mask(record.getPayloadHash()),
                    SensitiveDataSanitizer.mask(payloadHash));
            throw new IdempotencyRejectedException(IdempotencyRejectCode.PAYLOAD_MISMATCH,
                    "idempotency key was already used with a different request payload", traceId);
        }

        T replayed = deserializeResult(record.getResultJson(), resultType, traceId);
        log.info("[{}] duplicate request replayed terminal status={} errorCode={} key={}",
                traceId, record.getStatus(), record.getErrorCode(), record.getKey());
        return IdempotencyOutcome.replayed(record.getStatus(), replayed, record.getErrorCode(), traceId);
    }

    private <T> IdempotencyOutcome<T> handleConcurrentLoser(String callerId, String key, String payloadHash,
                                                            String traceId, Class<T> resultType,
                                                            Supplier<BusinessTerminal<T>> business) {
        Optional<IdempotencyRecord> winner;
        try {
            winner = store.find(callerId, key, properties.getTtl());
        } catch (StoreAccessException e) {
            throw new IdempotencyRejectedException(IdempotencyRejectCode.STORE_UNAVAILABLE,
                    "idempotency store is temporarily unavailable, request rejected (no side effect)", traceId);
        }
        if (winner.isPresent() && winner.get().getStatus() == IdempotencyStatus.IN_FLIGHT) {
            log.info("[{}] lost claim race, holder in-flight. key={}", traceId, key);
            throw new IdempotencyRejectedException(IdempotencyRejectCode.CONCURRENT_IN_FLIGHT,
                    "an identical request is currently being processed", traceId);
        }
        if (winner.isEmpty()) {
            // 极端情况下记录消失，保守拒绝而不是冒险执行
            throw new IdempotencyRejectedException(IdempotencyRejectCode.STORE_UNAVAILABLE,
                    "idempotency state is inconsistent, request rejected (no side effect)", traceId);
        }
        return handleExisting(winner.get(), payloadHash, traceId, resultType, business);
    }

    private <T> IdempotencyOutcome<T> runBusiness(IdempotencyRecord record, String traceId,
                                                  Class<T> resultType,
                                                  Supplier<BusinessTerminal<T>> business) {
        BusinessTerminal<T> terminal;
        try {
            terminal = business.get();
        } catch (RuntimeException ex) {
            // 业务层应已把下游失败转成终态；到达这里说明是未受控异常，
            // 标记 UNCERTAIN 而不是静默重试（副作用未知）
            log.error("[{}] uncontrolled exception during idempotent command, marking UNCERTAIN: {}",
                    traceId, ex.toString());
            terminal = BusinessTerminal.uncertain("INTERNAL_UNCERTAIN");
        }

        record.setStatus(terminal.getStatus());
        record.setErrorCode(terminal.getErrorCode());
        record.setCompletedAt(clock.instant());
        record.setResultJson(serializeResult(terminal.getResult(), traceId));

        try {
            store.complete(record, properties.getTtl());
        } catch (StoreAccessException e) {
            // 终态无法落库：副作用可能已发生且去重状态丢失，必须标记不确定而非静默重试
            log.error("[{}] failed to persist terminal status, marking UNCERTAIN. key={} status={}",
                    traceId, record.getKey(), terminal.getStatus());
            return IdempotencyOutcome.executed(IdempotencyStatus.UNCERTAIN, terminal.getResult(),
                    terminal.getStatus() == IdempotencyStatus.SUCCEEDED
                            ? "RESULT_PERSISTENCE_UNCERTAIN"
                            : terminal.getErrorCode(),
                    traceId);
        }

        log.info("[{}] business finished status={} errorCode={} key={}",
                traceId, terminal.getStatus(), terminal.getErrorCode(), record.getKey());
        return IdempotencyOutcome.executed(terminal.getStatus(), terminal.getResult(),
                terminal.getErrorCode(), traceId);
    }

    private String serializeResult(Object result, String traceId) {
        if (result == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.warn("[{}] failed to serialize idempotent result: {}", traceId, e.toString());
            return null;
        }
    }

    private <T> T deserializeResult(String json, Class<T> resultType, String traceId) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, resultType);
        } catch (JsonProcessingException e) {
            log.warn("[{}] failed to deserialize stored result: {}", traceId, e.toString());
            return null;
        }
    }

    private void validate(String callerId, String key, String payloadHash) {
        if (callerId == null || callerId.isBlank()) {
            throw new IllegalArgumentException("X-Caller-Id is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new IllegalArgumentException("payload hash is required");
        }
    }
}
