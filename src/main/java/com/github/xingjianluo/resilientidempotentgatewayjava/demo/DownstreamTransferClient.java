package com.github.xingjianluo.resilientidempotentgatewayjava.demo;

/**
 * 下游账务依赖（本地实现，不访问外部服务）。
 */
public interface DownstreamTransferClient {

    /**
     * 执行下游转账。实现可确定性成功、确定性失败或阻塞/超时。
     */
    TransferResult transfer(TransferRequest request, String traceId);
}
