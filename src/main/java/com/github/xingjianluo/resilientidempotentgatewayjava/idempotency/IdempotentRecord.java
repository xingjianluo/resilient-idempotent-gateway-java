package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** 一条幂等记录。 */
public class IdempotentRecord {

    private final String callerId;
    private final String idempotencyKey;
    private final String payloadHash;
    private final long createdAtMillis;
    private final Lock lock = new ReentrantLock();
    private final Condition completedCondition = lock.newCondition();

    private volatile RequestStatus status = RequestStatus.PROCESSING;
    private volatile String storedResultJson;
    private volatile String errorCode;
    private volatile long completedAtMillis;
    private volatile long expiresAtMillis;

    public IdempotentRecord(String callerId, String idempotencyKey, String payloadHash,
                            long createdAtMillis, long expiresAtMillis) {
        this.callerId = callerId;
        this.idempotencyKey = idempotencyKey;
        this.payloadHash = payloadHash;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
    }

    public String getCallerId() { return callerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPayloadHash() { return payloadHash; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public Lock getLock() { return lock; }
    public Condition getCompletedCondition() { return completedCondition; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public String getStoredResultJson() { return storedResultJson; }
    public void setStoredResultJson(String storedResultJson) { this.storedResultJson = storedResultJson; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public long getCompletedAtMillis() { return completedAtMillis; }
    public void setCompletedAtMillis(long completedAtMillis) { this.completedAtMillis = completedAtMillis; }
    public long getExpiresAtMillis() { return expiresAtMillis; }
    public void setExpiresAtMillis(long expiresAtMillis) { this.expiresAtMillis = expiresAtMillis; }

    public boolean isTerminal() {
        return status != RequestStatus.PROCESSING;
    }
}
