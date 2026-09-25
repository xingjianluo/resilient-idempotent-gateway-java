package com.github.xingjianluo.resilientidempotentgatewayjava.demo;

import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamErrorType;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 默认本地下游：直接成功。测试中使用可控的假实现。
 */
@Component
public class LocalTransferClient implements DownstreamTransferClient {

    @Override
    public TransferResult transfer(TransferRequest request, String traceId) {
        return new TransferResult(UUID.randomUUID().toString(), "COMPLETED", request.getAmountCents());
    }
}
