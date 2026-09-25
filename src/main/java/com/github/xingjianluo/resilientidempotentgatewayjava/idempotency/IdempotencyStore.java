package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/** 幂等存储抽象。 */
public interface IdempotencyStore {

    AcquireResult acquire(String callerId, String idempotencyKey, String payloadHash, long waitMillis);

    void complete(IdempotentRecord owner, RequestStatus status, String resultJson, String errorCode);

    boolean isAvailable();

    void setAvailable(boolean available);
}
