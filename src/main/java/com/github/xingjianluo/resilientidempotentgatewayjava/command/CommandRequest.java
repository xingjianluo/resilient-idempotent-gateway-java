package com.github.xingjianluo.resilientidempotentgatewayjava.command;

/** 命令入口请求载荷。 */
public record CommandRequest(String scenario, String orderId, Integer amount, String credential) {
}
