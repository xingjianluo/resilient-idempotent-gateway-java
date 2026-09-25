package com.github.xingjianluo.resilientidempotentgatewayjava.idempotency;

/**
 * 幂等存储访问异常，仅用于内部判定，不直接透出给调用方。
 */
public class StoreAccessException extends RuntimeException {
    public StoreAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
