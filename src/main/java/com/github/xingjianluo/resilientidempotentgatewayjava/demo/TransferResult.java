package com.github.xingjianluo.resilientidempotentgatewayjava.demo;

/**
 * 示例命令结果。
 */
public record TransferResult(String transferId, String status, long amountCents) {
}
