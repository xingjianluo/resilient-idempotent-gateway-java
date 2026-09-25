package com.github.xingjianluo.resilientidempotentgatewayjava.demo;

import com.github.xingjianluo.resilientidempotentgatewayjava.idempotency.BusinessTerminal;
import com.github.xingjianluo.resilientidempotentgatewayjava.log.SensitiveDataSanitizer;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.BusinessException;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamErrorType;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.DownstreamException;
import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilientCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 示例业务服务：转账命令。
 * <p>
 * 语义边界：
 * <ul>
 *     <li>参数校验失败 = 确定性业务失败（副作用未发生），返回 FAILED，可稳定重放；</li>
 *     <li>下游返回 {@link BusinessException} = 下游侧确定性业务拒绝，同样 FAILED；</li>
 *     <li>下游超时/连接类异常 = 副作用未知，返回 UNCERTAIN，上层禁止静默重试；</li>
 *     <li>下游熔断打开/隔离已满 = 调用未发生，确定性失败（BLOCKED）。</li>
 * </ul>
 */
@Service
public class TransferService {

    /** 下游账务依赖名，用于隔离资源命名。 */
    public static final String DEPENDENCY_LEDGER = "ledger";

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final ResilientCaller resilientCaller;
    private final DownstreamTransferClient client;

    public TransferService(ResilientCaller resilientCaller, DownstreamTransferClient client) {
        this.resilientCaller = resilientCaller;
        this.client = client;
    }

    /** 执行业务，始终返回终态（不向幂等层抛出下游异常）。 */
    public BusinessTerminal<TransferResult> execute(TransferRequest request, String traceId) {
        log.info("[{}] transfer input from={} to={} amount={} credential={}",
                traceId, request.getFromAccount(), request.getToAccount(), request.getAmountCents(),
                SensitiveDataSanitizer.mask(request.getCredential()));

        // 确定性业务校验：失败即终态 FAILED，副作用确定未发生
        if (request.getAmountCents() <= 0) {
            log.info("[{}] business validation failed: amount must be positive, got {}",
                    traceId, request.getAmountCents());
            return BusinessTerminal.failed("INVALID_AMOUNT");
        }
        if (isBlank(request.getFromAccount()) || isBlank(request.getToAccount())) {
            log.info("[{}] business validation failed: missing account", traceId);
            return BusinessTerminal.failed("INVALID_ACCOUNT");
        }
        if (request.getFromAccount().equals(request.getToAccount())) {
            log.info("[{}] business validation failed: same source and target account", traceId);
            return BusinessTerminal.failed("SAME_ACCOUNT");
        }
        if (isBlank(request.getCredential())) {
            log.info("[{}] business validation failed: missing credential", traceId);
            return BusinessTerminal.failed("MISSING_CREDENTIAL");
        }

        try {
            TransferResult result = resilientCaller.call(DEPENDENCY_LEDGER,
                    () -> client.transfer(request, traceId));
            log.info("[{}] transfer succeeded transferId={} status={}",
                    traceId, result.transferId(), result.status());
            return BusinessTerminal.success(result);
        } catch (BusinessException be) {
            // 下游确定性业务拒绝（如余额不足）：副作用确定未发生
            log.info("[{}] deterministic downstream business rejection code={}",
                    traceId, be.getBusinessErrorCode());
            return BusinessTerminal.failed(be.getBusinessErrorCode());
        } catch (DownstreamException de) {
            return mapDownstreamFailure(de, traceId);
        }
    }

    private BusinessTerminal<TransferResult> mapDownstreamFailure(DownstreamException de, String traceId) {
        DownstreamErrorType type = de.getErrorType();
        if (type == DownstreamErrorType.CIRCUIT_OPEN || type == DownstreamErrorType.BULKHEAD_FULL) {
            // 调用根本未发出，副作用确定未发生 -> 确定性失败
            log.warn("[{}] downstream call blocked type={}, treated as deterministic failure", traceId, type);
            return BusinessTerminal.failed(type.code());
        }
        if (de.isUncertain()) {
            // 超时/中断/未知 I/O：钱可能已扣，必须标记不确定，禁止静默重试
            log.warn("[{}] downstream result UNKNOWN type={}, marking idempotency status UNCERTAIN",
                    traceId, type);
            return BusinessTerminal.uncertain(type.code());
        }
        // 确定性远端错误（重试耗尽但每次都是确定错误）
        log.warn("[{}] downstream deterministic failure type={}", traceId, type);
        return BusinessTerminal.failed(type.code());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
