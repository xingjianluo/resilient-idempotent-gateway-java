package com.github.xingjianluo.resilientidempotentgatewayjava.resilience;

/**
 * 业务侧确定性失败异常（与下游失败相区分）。
 */
public class BusinessException extends RuntimeException {

    private final String businessErrorCode;

    public BusinessException(String businessErrorCode, String message) {
        super(message);
        this.businessErrorCode = businessErrorCode;
    }

    public String getBusinessErrorCode() {
        return businessErrorCode;
    }
}
