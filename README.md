# 并发安全的幂等命令入口与依赖降级链路

基于 Spring Boot 3 / Java 21 的本地可运行实现。提供：

1. **幂等命令入口**：按 `(X-Caller-Id, Idempotency-Key)` 收敛重复与并发提交，副作用只发生一次，返回稳定结论；
2. **下游依赖隔离与降级**：按依赖独立配置超时、重试、熔断、并发隔离，互不干扰；
3. **失败链路一致性**：部分成功显式标记 `UNCERTAIN` 而非静默重试，全链路 `traceId` 可关联，日志/响应无凭据与堆栈。

实现不依赖任何外部服务（数据库、Redis、HTTP 下游均为进程内模拟），验证全部在本地完成。

---

## 1. 快速验证

```bash
mvn clean test
```

- JDK 要求：21+（`pom.xml` 中通过 `java.version=21` 锁定 release）。
- 测试报告：`target/surefire-reports/`。
- 测试日志会在控制台打印【输入】【判定依据】【状态迁移】，可直接复盘每个断言。

测试覆盖（共 30 个用例）：

| 测试类 | 覆盖内容 |
| --- | --- |
| `IdempotencyStoreTest` | 重复收敛、同键不同载荷、TTL 过期重放、16/8 线程并发只执行一次、等待窗口外并发冲突、UNCERTAIN 阻断、存储不可用、调用方隔离 |
| `ResilienceEngineTest` | 超时不重试且标记未知、业务失败与超时可区分、瞬时失败重试耗尽并跳闸、隔离舱拒绝不影响熔断与其它依赖 |
| `CircuitBreakerTest` | CLOSED→OPEN→HALF_OPEN→CLOSED；试探失败重新 OPEN 并重置冷却 |
| `CircuitRecoveryTest` | 端到端熔断恢复：跳闸、OPEN 快速失败、试探失败重开、再次试探成功关闭 |
| `CommandServiceTest` | 成功收敛稳定返回、超时(504) vs 业务失败(422)、部分成功 UNCERTAIN 且重试不重复副作用、失败回放、瞬时重试、载荷冲突、存储宕机 fail-closed、凭据脱敏 |
| `ConcurrentCommandTest` | 24 个相同并发请求副作用恰好一次；并发不同载荷只有赢者执行 |
| `HttpEndpointTest` | MockMvc 端到端：头校验、状态码、traceId 复用、响应无凭据/堆栈、503 降级与恢复 |

手工体验（启动后端口随机，注意看日志）：

```bash
mvn spring-boot:run
curl -i -X POST http://127.0.0.1:<port>/api/commands \
  -H 'X-Caller-Id: svc-a' -H 'Idempotency-Key: k-1' -H 'Content-Type: application/json' \
  -d '{"scenario":"success","orderId":"O-1","amount":100,"credential":"will-be-redacted"}'
# 再发一次完全相同的请求 => 200，响应 "replay":true，下游不再被调用
```

---

## 2. 幂等语义

### 2.1 键与作用域

- 幂等范围 = 复合键 `callerId + idempotencyKey`，不同调用方复用同一键互不影响。
- 请求头：`X-Caller-Id`、`Idempotency-Key` 必填；`X-Trace-Id` 可选（合法则复用）。
- 载荷指纹：对**业务字段**（`scenario|orderId|amount`）做 SHA-256；`credential` 不参与指纹、不落日志。

### 2.2 收敛规则（结论全部可区分）

| 场景 | 判定依据 | HTTP | code |
| --- | --- | --- | --- |
| 首次提交 | 原子占位成功（`putIfAbsent` PROCESSING） | 200 | `OK` |
| 完成后重复提交（同键同载荷，未过期） | 终态记录命中，回放首次结论 | 200 | `OK`（`replay=true`） |
| 首次失败后重复提交 | 回放原错误码（业务失败 422 / 下游失败 502），不重新执行 | 422/502 | 原 code（`replay=true`） |
| 同键不同载荷 | 指纹不一致，**拒绝执行**，原记录保留 | 409 | `PAYLOAD_MISMATCH` |
| 并发提交（等待窗口内首个请求完成） | 等待条件变量后读到终态，收敛为重放 | 200 | `OK`（`replay=true`） |
| 并发提交（首个请求在等待窗口内未完成） | 不排队、不执行，显式冲突 | 409 | `CONCURRENT_SUBMISSION` |
| TTL 过期后重放 | `now >= expiresAt`（墓碑仍保留以便判定） | 410 | `IDEMPOTENCY_EXPIRED` |
| 首次结论不确定时再来 | 记录状态为 `UNCERTAIN` | 409 | `RESULT_UNCERTAIN` |
| 存储层不可用 / 存储异常 | 无法保证去重，**fail-closed 拒绝执行** | 503 | `STORE_UNAVAILABLE` |
| 缺少幂等头 / 请求体非法 | 入口校验 | 400 | `MISSING_IDEMPOTENCY_HEADER` / `INVALID_REQUEST` |

### 2.3 并发安全如何保证

1. 存储先对复合键做 **原子 `putIfAbsent` 插入 `PROCESSING` 占位记录**，只有一个线程成为 owner；
2. owner 执行业务并 `complete()` 落终态（`SUCCESS` / `FAILED` / `UNCERTAIN`）；
3. 其它线程在该记录的 `ReentrantLock + Condition` 上等待至多 `acquire-wait-millis`，
   完成则收敛为 REPLAY，未完成则返回 `CONCURRENT_SUBMISSION`；
4. 终态刷新 TTL（TTL 自完成时刻起算），过期记录作为墓碑继续保留，重放返回 `410` 而不是被当成新请求。

### 2.4 不确定状态（部分成功）的处理

下游**超时**意味着“请求可能已经到达并生效，但响应丢失”。本系统：

- **绝不自动重试超时请求**（重试可能造成重复扣款）；
- 将幂等记录置为 `UNCERTAIN`，对外返回 `504 DOWNSTREAM_TIMEOUT`；
- 此后相同键的请求一律返回 `409 RESULT_UNCERTAIN`，提示先与下游对账再决定，
  而不是静默重放或重新执行。

结果确定的失败（业务拒绝、连接根本未建立的瞬时错误）才进入重试/重放路径。

---

## 3. 下游隔离与降级规则

四类策略按**依赖名**各自维护一份状态（熔断器、信号量），互不共享、互不干扰。
`payment` 为业务使用的模拟依赖；未配置的依赖回退到 `default` 规则。

执行顺序：

```
熔断门(tryAcquirePermission) → 并发隔离门(tryAcquire 信号量) → [每次尝试] 超时(future.get) → 重试判定
```

### 3.1 超时（timeout）

- 使用独立的虚拟线程池提交下游调用，`future.get(callTimeoutMillis)` 控制预算；
- 超时即 `cancel(true)` 并立即返回；**超时不可重试，`outcomeKnown=false`**；
- 对外：`504 DOWNSTREAM_TIMEOUT`，记录 `UNCERTAIN`。

### 3.2 重试（retry）

- 仅当异常同时满足 `retryable=true` 且 `outcomeKnown=true`（如连接重置、未产生副作用）时才重试；
- 固定退避 `backoff-millis`；总尝试次数 `max-attempts`（含首次）；
- 业务拒绝（`retryable=false`）立即返回；超时从不重试；
- 重试耗尽：`502 DOWNSTREAM_RETRY_EXHAUSTED`。

### 3.3 熔断（circuit breaker）

- CLOSED 下连续失败（每次尝试的确定性失败都计数）达到 `failure-threshold` → OPEN；
- OPEN 期间请求立即被拒：`502 DOWNSTREAM_CIRCUIT_OPEN`，**不触达下游、不消耗信号量**；
- 冷却 `open-millis` 结束后，仅**一个**线程 CAS 进入 HALF_OPEN 成为试探请求，其余继续拒绝；
- **试探成功** → CLOSED（计数清零，日志 `HALF_OPEN -> CLOSED`）；
- **试探失败** → OPEN（冷却时间从此刻重新计时，日志 `HALF_OPEN -> OPEN`）；
  两类恢复在状态、日志与对外结论上严格可区分。

### 3.4 并发隔离（bulkhead）

- 每个依赖独立公平信号量 `bulkhead-max-concurrent`，占满立即返回
  `502 DOWNSTREAM_BULKHEAD_FULL`，不排队；
- 被隔离的请求未真正发出，**不计入熔断失败数**；一个依赖占满不影响其它依赖。

### 3.5 错误类型映射（对外不含堆栈）

| 内部类型 | HTTP | code |
| --- | --- --- | --- |
| 下游超时（结果未知） | 504 | `DOWNSTREAM_TIMEOUT` |
| 业务确定性拒绝 | 422 | `BUSINESS_REJECTED` |
| 可重试错误耗尽 | 502 | `DOWNSTREAM_RETRY_EXHAUSTED` |
| 熔断打开 | 502 | `DOWNSTREAM_CIRCUIT_OPEN` |
| 隔离舱占满 | 502 | `DOWNSTREAM_BULKHEAD_FULL` |
| 未受控下游异常 | 502 | `DOWNSTREAM_ERROR` |
| 内部未捕获异常（结果未知） | 500 | `INTERNAL_UNCERTAIN` / `INTERNAL_ERROR` |

所有异常由 `GlobalExceptionHandler` 兜底：只返回稳定错误码与脱敏消息，异常类型与堆栈仅进日志。

---

## 4. 追踪标识与敏感信息

- 每个请求生成 `tr-<32hex>` 的 `traceId`；请求头 `X-Trace-Id` 满足 `[A-Za-z0-9._-]{8,64}` 时复用，否则重新生成，防止日志注入。
- `traceId` 写入 SLF4J MDC，日志格式为
  `时间 级别 [traceId=xxx] logger - msg`，入口、业务、下游调用同一条链可关联。
- `SensitiveDataSanitizer` 对 `credential/password/token/secret/authorization/api-key` 等字段
  在 JSON、`key=value`、`Bearer` 三种形态下统一打码为 `***REDACTED***`，并剥离 CR/LF。
- 模拟下游只接收业务字段；`credential` 不参与指纹、不写入成功数据、不出现在响应中
  （`HttpEndpointTest` / `CommandServiceTest` 均有断言）。

---

## 5. 可配置项

前缀 `gateway.`（见 `application.properties`，均可按环境覆盖）：

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `gateway.idempotency.ttl-seconds` | 300 | 幂等记录存活秒数；终结时自完成时刻重新计时；过期重放返回 410 |
| `gateway.idempotency.acquire-wait-millis` | 200 | 并发提交等待首个请求完成的窗口；超时返回 409 |
| `gateway.resilience.dependencies.<name>.call-timeout-millis` | 1000 | 单次下游调用超时（毫秒），超时不重试 |
| `...max-attempts` | 2 | 总尝试次数（含首次） |
| `...backoff-millis` | 20 | 重试固定退避（毫秒） |
| `...failure-threshold` | 5 | 连续失败多少次跳闸 |
| `...open-millis` | 2000 | OPEN 冷却时长（毫秒），之后允许一次 HALF_OPEN 试探 |
| `...bulkhead-max-concurrent` | 10 | 该依赖最大并发，占满立即降级 |

`<name>` 为依赖名；`default` 为兜底规则，当前业务使用 `payment`。

### 模拟下游场景（请求体 `scenario` 字段）

| scenario | 行为 |
| --- | --- |
| `success` | 稳定成功 |
| `business-error` | 确定性业务拒绝（422，不重试） |
| `timeout` | 阻塞超过超时预算（504，UNCERTAIN） |
| `timeout-then-success` | 首次“已生效但响应丢失”，再次直接调用会成功；用于验证幂等层不会自动重试 |
| `flaky` | 按订单计数，前 2 次瞬时连接失败、随后成功（可重试） |

---

## 6. 代码结构

```
config/      GatewayProperties（@ConfigurationProperties）、装配（虚拟线程超时池、ResilienceEngine）
trace/       TraceIds（生成/校验 traceId）
log/         SensitiveDataSanitizer（凭据打码、CRLF 剥离）
idempotency/ RequestStatus / AcquireOutcome（可区分结论）、IdempotentRecord、
             IdempotencyStore + InMemoryIdempotencyStore（原子占位、条件等待、TTL、故障注入）
resilience/  CircuitState / DownstreamErrorType / DownstreamException /
             DownstreamOutcome、CircuitBreaker（CLOSED/OPEN/HALF_OPEN）、ResilienceEngine
command/     CommandService（编排核心）、SimulatedPaymentClient（本地模拟下游）、请求/响应模型
web/         CommandController、GlobalExceptionHandler
```
