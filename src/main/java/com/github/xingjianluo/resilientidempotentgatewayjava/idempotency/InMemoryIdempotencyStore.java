package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconcileStoreResult;
import com.github.xingjianluo.resilientidempotentgatewayjava.reconciliation.ReconciliationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地内存幂等存储（无外部依赖，便于本地验证）。
 * <p>
 * 使用 {@link ConcurrentHashMap#compute} 保证“检查 + 占位/接管”原子性：
 * 同一 (callerId,key) 并发占位时恰好一个成功。终态记录超过 TTL 视为过期，
 * 允许被新记录覆盖（旧键重放由上层判定为 {@code KEY_EXPIRED}）。
 * <p>
 * {@code available=false} 时所有访问抛出 {@link StoreAccessException}，
 * 用于模拟存储层暂时不可用；入口据此 fail-closed 降级。
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private record CompositeKey(String callerId, String key) {
    }

    private final ConcurrentMap<CompositeKey, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile boolean available = true;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    /** 模拟存储层健康状态（测试用）。 */
    public void setAvailable(boolean available) {
        log.info("idempotency store availability switched: {}", available);
        this.available = available;
    }

    private void ensureAvailable() {
        if (!available) {
            throw new StoreAccessException("idempotency store is unavailable", null);
        }
    }

    private CompositeKey composite(String callerId, String key) {
        return new CompositeKey(callerId, key);
    }

    private boolean terminalExpired(IdempotencyRecord record, Duration ttl) {
        Instant base = record.getCompletedAt() != null ? record.getCompletedAt() : record.getCreatedAt();
        return base.plus(ttl).isBefore(clock.instant());
    }

    @Override
    public Optional<IdempotencyRecord> find(String callerId, String key, Duration ttl) {
        ensureAvailable();
        CompositeKey ck = composite(callerId, key);
        IdempotencyRecord existing = records.get(ck);
        if (existing == null) {
            return Optional.empty();
        }
        if (existing.getStatus() != IdempotencyStatus.IN_FLIGHT && terminalExpired(existing, ttl)) {
            return Optional.empty();
        }
        return Optional.of(existing);
    }

    @Override
    public Optional<IdempotencyRecord> findIncludingExpired(String callerId, String key) {
        ensureAvailable();
        return Optional.ofNullable(records.get(composite(callerId, key)));
    }

    @Override
    public boolean claim(IdempotencyRecord record, Duration ttl) {
        ensureAvailable();
        CompositeKey ck = composite(record.getCallerId(), record.getKey());
        boolean[] claimed = {false};
        records.compute(ck, (k, existing) -> {
            if (existing == null
                    || (existing.getStatus() == IdempotencyStatus.IN_FLIGHT
                        ? false
                        : terminalExpired(existing, ttl))) {
                claimed[0] = true;
                return record;
            }
            claimed[0] = false;
            return existing;
        });
        log.debug("claim callerId={} key={} outcome={}", record.getCallerId(), record.getKey(), claimed[0]);
        return claimed[0];
    }

    @Override
    public boolean takeoverStaleInFlight(IdempotencyRecord expected, IdempotencyRecord replacement) {
        ensureAvailable();
        CompositeKey ck = composite(expected.getCallerId(), expected.getKey());
        boolean[] took = {false};
        records.computeIfPresent(ck, (k, current) -> {
            if (current == expected && current.getStatus() == IdempotencyStatus.IN_FLIGHT) {
                took[0] = true;
                log.info("stale IN_FLIGHT record taken over callerId={} key={}",
                        expected.getCallerId(), expected.getKey());
                return replacement;
            }
            return current;
        });
        return took[0];
    }

    @Override
    public void complete(IdempotencyRecord record, Duration ttl) {
        ensureAvailable();
        // 内存实现中 record 即 map 内对象，终态字段为 volatile，更新对其他线程可见；
        // 再次 put 保证发布语义。
        records.put(composite(record.getCallerId(), record.getKey()), record);
        log.debug("record completed callerId={} key={} status={} errorCode={}",
                record.getCallerId(), record.getKey(), record.getStatus(), record.getErrorCode());
    }

    @Override
    public ReconcileStoreResult reconcile(String callerId, String key, Duration ttl,
                                          ReconciliationInfo info) {
        ensureAvailable();
        CompositeKey ck = composite(callerId, key);
        ReconcileStoreResult[] result = new ReconcileStoreResult[1];
        records.compute(ck, (k, existing) -> {
            if (existing == null) {
                result[0] = new ReconcileStoreResult(ReconcileStoreResult.Status.NOT_FOUND, null);
                return null;
            }
            if (existing.getStatus() != IdempotencyStatus.IN_FLIGHT && terminalExpired(existing, ttl)) {
                result[0] = new ReconcileStoreResult(ReconcileStoreResult.Status.EXPIRED, existing);
                return existing;
            }
            if (!existing.isPendingReconciliation()) {
                // 非待对账：自然终态（SUCCEEDED/FAILED）、IN_FLIGHT，或已被回写过
                result[0] = new ReconcileStoreResult(ReconcileStoreResult.Status.NOT_PENDING, existing);
                return existing;
            }
            // 原子生效：UNCERTAIN -> 权威稳定终态
            existing.setStatus(info.getVerdict().targetStatus());
            existing.setErrorCode(switch (info.getVerdict()) {
                case CONFIRMED_SUCCEEDED -> null;
                case CONFIRMED_FAILED -> "RECONCILED_CONFIRMED_FAILED";
            });
            existing.setReconciliation(info);
            result[0] = new ReconcileStoreResult(ReconcileStoreResult.Status.APPLIED, existing);
            return existing;
        });
        log.info("reconcile attempt callerId={} key={} verdict={} outcome={}",
                callerId, key, info.getVerdict(), result[0].status());
        return result[0];
    }

    @Override
    public long pendingReconciliationCount(Duration ttl, boolean includeExpired) {
        ensureAvailable();
        // 统计的同时惰性清理已过期终态记录，避免内存随运行时间无界增长；
        // includeExpired=true 时，被清理前仍处于待对账的记录也计入本次观测口径。
        long count = 0;
        var it = records.entrySet().iterator();
        while (it.hasNext()) {
            IdempotencyRecord record = it.next().getValue();
            boolean pending = record.isPendingReconciliation();
            if (record.getStatus() != IdempotencyStatus.IN_FLIGHT && terminalExpired(record, ttl)) {
                it.remove();
                if (pending && includeExpired) {
                    count++;
                }
                continue;
            }
            if (pending) {
                count++;
            }
        }
        return count;
    }
}
