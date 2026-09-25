package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

import com.github.xingjianluo.resilientidempotentgatewayjava.config.GatewayProperties;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于内存的幂等存储。
 *
 * <p>键为 {@code callerId + "\u0000" + idempotencyKey}，按调用方隔离。
 * 通过"先原子插入 PROCESSING 占位记录、再执行业务"保证并发提交只有一个请求真正执行；
 * 重复请求在条件变量上等待首个请求完成后读取稳定结果。
 *
 * <p>{@code available=false} 用于模拟存储层暂时不可用，以验证降级结论。
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private final Map<String, IdempotentRecord> records = new ConcurrentHashMap<>();
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final GatewayProperties properties;

    public InMemoryIdempotencyStore(GatewayProperties properties) {
        this.properties = properties;
    }

    private static String compositeKey(String callerId, String idempotencyKey) {
        return callerId + '\u0000' + idempotencyKey;
    }

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    @Override
    public void setAvailable(boolean available) {
        boolean before = this.available.getAndSet(available);
        log.warn("idempotency store availability switched {} -> {}", before, available);
    }

    /** 测试辅助：查看某条记录当前状态。 */
    public Optional<IdempotentRecord> peek(String callerId, String idempotencyKey) {
        return Optional.ofNullable(records.get(compositeKey(callerId, idempotencyKey)));
    }

    /** 测试辅助：清空全部记录。 */
    public void clear() {
        records.clear();
    }

    private long ttlMillis() {
        return Math.max(1L, properties.getIdempotency().getTtlSeconds()) * 1000L;
    }

    @Override
    public AcquireResult acquire(String callerId, String idempotencyKey, String payloadHash, long waitMillis) {
        if (!available.get()) {
            log.warn("acquire rejected: store unavailable callerId={} key={}", callerId, idempotencyKey);
            return AcquireResult.of(AcquireOutcome.STORE_UNAVAILABLE, null);
        }
        long now = System.currentTimeMillis();
        String ck = compositeKey(callerId, idempotencyKey);

        IdempotentRecord created = new IdempotentRecord(
                callerId, idempotencyKey, payloadHash, now, now + ttlMillis());

        // 原子占位：只有一个线程能放入新记录
        IdempotentRecord existing = records.putIfAbsent(ck, created);
        if (existing == null) {
            log.info("acquire ACCEPTED (first execution) key={}", idempotencyKey);
            return AcquireResult.of(AcquireOutcome.ACCEPTED, created);
        }

        existing.getLock().lock();
        try {
            if (existing.getStatus() == RequestStatus.PROCESSING) {
                // 并发提交：等待首个请求完成，等待窗口内完成则收敛为 REPLAY
                long deadline = System.currentTimeMillis() + Math.max(0L, waitMillis);
                long remaining = deadline - System.currentTimeMillis();
                while (existing.getStatus() == RequestStatus.PROCESSING && remaining > 0) {
                    if (!existing.getCompletedCondition().await(remaining, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    remaining = deadline - System.currentTimeMillis();
                }
                if (existing.getStatus() == RequestStatus.PROCESSING) {
                    log.warn("acquire CONCURRENT: peer still processing after {}ms key={}",
                            waitMillis, idempotencyKey);
                    return AcquireResult.of(AcquireOutcome.CONCURRENT, existing);
                }
            }

            // 已终结：按不确定状态、过期、载荷一致性顺序给出可区分结论
            if (existing.getStatus() == RequestStatus.UNCERTAIN) {
                log.warn("acquire UNCERTAIN_REPLAY key={}", idempotencyKey);
                return AcquireResult.of(AcquireOutcome.UNCERTAIN_REPLAY, existing);
            }
            if (System.currentTimeMillis() >= existing.getExpiresAtMillis()) {
                log.warn("acquire EXPIRED_REPLAY key={} expiredAt={}",
                        idempotencyKey, existing.getExpiresAtMillis());
                return AcquireResult.of(AcquireOutcome.EXPIRED_REPLAY, existing);
            }
            if (!Objects.equals(existing.getPayloadHash(), payloadHash)) {
                log.warn("acquire PAYLOAD_MISMATCH key={}", idempotencyKey);
                return AcquireResult.of(AcquireOutcome.PAYLOAD_MISMATCH, existing);
            }
            log.info("acquire REPLAY (duplicate collapsed) key={} status={}",
                    idempotencyKey, existing.getStatus());
            return AcquireResult.of(AcquireOutcome.REPLAY, existing);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return AcquireResult.of(AcquireOutcome.STORE_UNAVAILABLE, existing);
        } finally {
            existing.getLock().unlock();
        }
    }

    @Override
    public void complete(IdempotentRecord owner, RequestStatus status, String resultJson, String errorCode) {
        Objects.requireNonNull(owner, "owner record");
        Objects.requireNonNull(status, "status");
        IdempotentRecord stored = records.get(compositeKey(owner.getCallerId(), owner.getIdempotencyKey()));
        if (stored != owner) {
            throw new IllegalStateException("complete() rejected: record is not the current owner");
        }
        long now = System.currentTimeMillis();
        owner.getLock().lock();
        try {
            owner.setStatus(status);
            owner.setStoredResultJson(resultJson);
            owner.setErrorCode(errorCode);
            owner.setCompletedAtMillis(now);
            // 终结时刷新存活窗口：TTL 自完成时刻起算
            owner.setExpiresAtMillis(now + ttlMillis());
            owner.getCompletedCondition().signalAll();
            log.info("record completed key={} status={} errorCode={}",
                    owner.getIdempotencyKey(), status, errorCode);
        } finally {
            owner.getLock().unlock();
        }
    }
}
