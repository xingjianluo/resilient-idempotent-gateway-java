# 并发安全的幂等命令入口与依赖降级链路

基于 Spring Boot 3 / Java 21 的本地可验证实现。提供：

1. **幂等命令入口**：以 `调用方标识 + 幂等键` 收敛重复 / 并发提交，副作用只发生一次，结果稳定重放；
2. **下游依赖隔离与降级**：超时、重试、熔断、并发隔离四个策略可配置且互不干扰，失败映射为可区分的对外错误类型；
3. **失败一致性**：下游结果未知时显式标记 `UNCERTAIN`，不静默重试；全链路 traceId 关联；日志对凭据脱敏；
4. **对账回写闭环**：`UNCERTAIN` 记录可由权威对账结论一次性回写为稳定终态（成功 / 未成功），
   之后同键提交直接重放该结论；待对账积压有可配置上限与观测端点，到上限时明确降级。

实现不依赖任何外部服务或真实凭据；所有测试均在本地 JVM 内完成。

---

## 1. HTTP 用法

```
POST /commands/transfers
Header:
  X-Caller-Id:     必填，调用方标识
  Idempotency-Key: 必填，幂等键
  X-Trace-Id:      可选，入站追踪标识（缺省自动生成，并通过响应头 X-Trace-Id 回写）
Body (JSON):
  {"fromAccount":"A","toAccount":"B","amountCents":100,"credential":"..."}
```

成功响应（200）：

```json
{
  "traceId": "trace-success",
  "idempotencyStatus": "SUCCEEDED",
  "replayed": false,
  "errorCode": null,
  "result": { "transferId": "TX-1", "status": "COMPLETED", "amountCents": 100 }
}
```

重复提交同 `(X-Caller-Id, Idempotency-Key)` 且同载荷时，业务不再执行，返回 `replayed=true`
与首次完全一致的终态结果。

---

## 2. 幂等语义

收敛维度为 **(callerId, idempotencyKey)**；对请求体做 SHA-256 摘要得到 `payloadHash`。
记录状态机：

```
IN_FLIGHT ──业务成功──────────▶ SUCCEEDED（终态，可稳定重放）
          ──确定性业务失败────▶ FAILED   （终态，可稳定重放失败结论）
          ──下游结果未知──────▶ UNCERTAIN（待对账：只重放不确定提示，禁止静默重试）
UNCERTAIN ──对账回写（仅一次）▶ SUCCEEDED / FAILED（稳定终态，之后同键提交直接重放该结论）
```

### 判定矩阵

| 场景 | 判定依据 | 结论 | HTTP |
|---|---|---|---|
| 首次请求 | 无记录，`claim` 原子占位成功 | 执行业务，`IN_FLIGHT→终态` | 200 / 422 / 202 |
| 重复提交（终态已存在，载荷一致） | 命中 SUCCEEDED/FAILED/UNCERTAIN | 重放稳定结果，`replayed=true`，**不再执行** | 与首次一致 |
| 并发重复提交（首个仍在执行） | 占位竞争落败且记录为 `IN_FLIGHT` | `IDEMPOTENCY_CONCURRENT_IN_FLIGHT` | 409 |
| 同键不同载荷 | 终态记录的 `payloadHash` 与本次不一致 | `IDEMPOTENCY_PAYLOAD_MISMATCH` | 422 |
| 键过期后重放 | `completedAt + ttl < now` | `IDEMPOTENCY_KEY_EXPIRED`，须更换新键 | 409 |
| 存储暂时不可用（读/占位/接管） | 抛出存储访问异常 | **fail-closed**：`IDEMPOTENCY_STORE_UNAVAILABLE`，绝不执行业务 | 503 |
| 持有者崩溃留下卡死的 IN_FLIGHT | 占位时间超过 `in-flight-stale-timeout` | 后续请求**原子接管**后执行（只有一个能接管成功） | 正常 |

关键保证：

- **副作用一次**：`ConcurrentHashMap.compute` 保证“检查 + 占位”原子，并发下恰好一个请求执行；
  已在 16 线程并发测试中验证。
- **部分成功不重复副作用**：终态落库后所有重试都只重放结果；终态本身无法落库时返回
  `UNCERTAIN`（`RESULT_PERSISTENCE_UNCERTAIN`），绝不重新执行业务。
- **不确定而非静默重试**：下游超时 / 连接中断等“钱可能已扣”的情况标记 `UNCERTAIN`，
  重复请求只重放该结论（HTTP 202，`idempotencyStatus=UNCERTAIN`），需调用方凭 traceId 对账。

> 设计取舍：幂等键过期后**拒绝同键重放**（避免与“过期前的旧结果”语义混淆），调用方需使用新键。

---

## 3. 对账回写（未知态闭环）

下游超时后入口返回 `UNCERTAIN`（202），调用方拿 traceId 去对账；对账得出权威结论后，
通过回写入口把结论补写回来，闭环未知态：

```
POST /commands/reconciliations
Header:
  X-Caller-Id:     必填，与原命令一致
  Idempotency-Key: 必填，与原命令一致
  X-Trace-Id:      可选，缺省自动生成
Body (JSON):
  {"verdict":"CONFIRMED_SUCCEEDED" | "CONFIRMED_FAILED",
   "reconciledBy":"ops-zhang", "reason":"ledger statement #881 confirmed"}

GET /commands/reconciliations/backlog   # 待对账积压观测：{pending, maxPending, includeExpired}
```

### 回写语义与规则

- **仅“结果未知、尚未对账”的记录可被回写**：`UNCERTAIN` 且从未回写过。
  回写原子生效后记录迁移为 `SUCCEEDED` / `FAILED` 稳定终态，之后同键提交**直接返回该结论
  并标 `replayed=true`**，不会再回未知；`reconciledBy / reconciledAt / reason / verdict /
  reconciliationTraceId` 随记录保存，日志与响应经脱敏、不含凭据。
- **已成功 / 已失败的记录不可被回写改掉**：自然终态（含 IN_FLIGHT）一律
  `RECONCILE_NOT_PENDING`（409）。
- **并发与重复回写**：存储层 `compute` 保证同一键只有一次回写真正生效（`applied=true`）。
  同结论的其余请求幂等拿到已生效结论（`applied=false`，HTTP 200）；
  **矛盾结论**（一次说成功、一次说失败）不会两个都写入——落败方得到
  `RECONCILE_CONFLICT`（409），响应带出胜出方的 `effectiveVerdict / reconciledBy /
  reconciledAt / reconciliationTraceId`，能明确看出被谁抢先。
- **可区分的拒绝**（不混为笼统错误）：

  | 场景 | 错误码 | HTTP |
  |---|---|---|
  | 键不存在 | `RECONCILE_KEY_NOT_FOUND` | 404 |
  | 键已过期 | `RECONCILE_KEY_EXPIRED` | 410 |
  | 不在待对账状态（SUCCEEDED/FAILED/IN_FLIGHT） | `RECONCILE_NOT_PENDING` | 409 |
  | 与已生效回写结论矛盾 | `RECONCILE_CONFLICT` | 409 |
  | 存储暂时不可用 | `IDEMPOTENCY_STORE_UNAVAILABLE` | 503 |

- **兼容范围（含改动前遗留记录）**：本次改动前产生的 `UNCERTAIN` 记录没有回写元数据
  （`reconciliation=null`），一律视为“尚未对账”，可被正常回写与重放，不会因结构差异
  报错或被当成全新请求；回写后行为与新产生的记录完全一致。

### 待对账积压上限与观测

状态保存在本机内存，待对账记录不能无限堆积：

- `gateway.idempotency.reconciliation-max-pending`（默认 10000）：待对账
  （`UNCERTAIN` 且未回写、未过期）记录数上限。**到上限时入口降级**：全新键的提交被拒绝
  （`RECONCILE_BACKLOG_FULL`，503，明确报错而非静默丢弃，不产生副作用、不留新记录）；
  已有键的重放与对账回写**不受影响**。积压随回写消化或记录过期清理而下降后，新键自动恢复受理。
  设为 `0` 可关闭该降级。
- `GET /commands/reconciliations/backlog` 实时返回 `{pending, maxPending, includeExpired}`；
  积压判定与回写日志也会打印当前 `pending/max`。
- `gateway.idempotency.reconciliation-backlog-include-expired`（默认 false）：观测口径，
  是否把已过期但尚未惰性清理的待对账记录也计入 `pending`。统计的同时会惰性清理已过期
  终态记录，保证内存不随运行时间无界增长。

---

## 4. 下游降级与恢复规则

`ResilientCaller` 按依赖名（如 `ledger`）隔离出独立的 **熔断器 / 信号量隔离 / 线程池**，
四个策略职责独立、互不干扰，组合顺序为：

1. **熔断门禁**：`OPEN` 直接快速失败；`HALF_OPEN` 只放行顺序试探；
2. **并发隔离**：信号量槽位满立即失败（整次调用含重试只占一个槽位）；
3. **超时**：每次尝试独立计时，超时取消 `Future` 并中断执行线程；
4. **重试**：仅对可能瞬时恢复的 `TIMEOUT` / `REMOTE_ERROR` 重试，指数退避；
   熔断打开 / 隔离已满不重试。

### 错误类型映射（对外可区分，不含内部堆栈）

| 错误码 | 含义 | 副作用是否未知 | HTTP |
|---|---|---|---|
| `DOWNSTREAM_TIMEOUT` | 单次调用超时 | 是（uncertain） | 504 |
| `DOWNSTREAM_REMOTE_ERROR` | 远端确定性错误 | 参数类为否，其余为是 | 502 |
| `DOWNSTREAM_RETRY_EXHAUSTED` | 重试耗尽 | 沿用末次错误的不确定性 | 502 |
| `DOWNSTREAM_CIRCUIT_OPEN` | 熔断打开，调用未发出 | 否 | 503 |
| `DOWNSTREAM_BULKHEAD_FULL` | 并发隔离槽位满，调用未发出 | 否 | 503 |
| 业务码（如 `INVALID_AMOUNT`、`INSUFFICIENT_FUNDS`） | 业务确定性失败 | 否 | 422 |

> 业务异常（`BusinessException`）穿透降级层、**不计入熔断失败率**；只有下游失败才计入。

### 熔断器状态机与恢复

```
CLOSED ──窗口内失败率 ≥ 阈值（且样本数 ≥ minimum-calls）──▶ OPEN
OPEN   ──等待 open-duration，第一个请求到来──────────────▶ HALF_OPEN
HALF_OPEN ──连续 trial-requests 次试探全部成功──────────▶ CLOSED（恢复）
          ──任意一次试探失败────────────────────────────▶ OPEN（重新冷却）
```

“试探成功”与“试探失败”被显式区分：恢复必须连续 N 次试探成功；试探在途期间其他请求快速失败，
保证试探结果可判定。

### 业务层（TransferService）对下游结论的归并

- 参数校验失败、下游 `BusinessException`、熔断打开 / 隔离已满 → `FAILED`（副作用确定未发生，可重放）；
- 下游超时 / 未知 I/O / 重试耗尽但不确定 → `UNCERTAIN`（标记而非重试）；
- 正常返回 → `SUCCEEDED`。

---

## 5. 追踪与敏感信息

- `TraceFilter` 为每个 HTTP 请求建立或透传 `X-Trace-Id`，写入 SLF4J MDC；
  `ResilientCaller` 将 MDC 传播到依赖线程，入口 / 业务 / 下游日志共享同一 traceId，
  响应头与错误体都回带 traceId。
- `SensitiveDataSanitizer` 对日志与任何文本中的 `password / passwd / token / secret /
  credential / authorization / apiKey` 字段（大小写不敏感）及 `Bearer ...` 头脱敏为 `***`；
  统一错误体只含 `traceId + code + message`，内部异常堆栈仅在服务端日志记录，不向外透出。

---

## 6. 可配置项

`src/main/resources/application.properties`（前缀 / 默认值）：

```properties
# 幂等
gateway.idempotency.ttl=24h                    # 终态记录存活时间；过期同键重放被拒绝
gateway.idempotency.in-flight-stale-timeout=30s  # IN_FLIGHT 卡死判定阈值，超时后允许原子接管
gateway.idempotency.reconciliation-max-pending=10000  # 待对账积压上限；到上限拒绝新键（0=关闭）
gateway.idempotency.reconciliation-backlog-include-expired=false  # 积压观测口径是否含已过期未清理记录

# 下游降级（对所有依赖生效；也可按需扩展为按依赖覆盖）
gateway.resilience.timeout=2s                  # 单次尝试超时
gateway.resilience.max-attempts=3              # 最大尝试次数（含首次，1=不重试）
gateway.resilience.retry-backoff=50ms          # 重试退避基数（指数退避）
gateway.resilience.circuit-minimum-calls=5     # 熔断判定所需最小样本数
gateway.resilience.circuit-failure-rate-threshold=0.5  # 熔断失败率阈值
gateway.resilience.circuit-open-duration=10s   # OPEN 冷却时长，过后进入 HALF_OPEN
gateway.resilience.circuit-trial-requests=2    # 恢复所需连续试探成功次数
gateway.resilience.bulkhead-max-concurrent=10  # 单依赖信号量并发上限
gateway.resilience.executor-pool-size=20       # 单依赖隔离线程池大小
```

测试使用 `src/test/resources/application.properties` 中的小超时常量（200ms 等），保证快速稳定。

---

## 7. 本地验证

无需任何外部服务 / 数据库 / 真实凭据：

```bash
# 全量测试（45 个用例）
mvn test

# 只跑幂等 / 对账回写 / 降级 / 端到端 / 脱敏
mvn test -Dtest=IdempotentCommandServiceTest
mvn test -Dtest=ReconciliationServiceTest
mvn test -Dtest=ReconciliationE2ETest
mvn test -Dtest=ResilientCallerTest
mvn test -Dtest=CommandControllerE2ETest
mvn test -Dtest=SensitiveDataSanitizerTest
```

覆盖的关键场景：

- 重复提交副作用一次且结果稳定；不同调用方同键互不干扰；
- **16 线程并发重复提交恰好执行一次**，其余得到 `CONCURRENT_IN_FLIGHT`，完成后重放；
- 同键不同载荷 `PAYLOAD_MISMATCH`；键过期重放 `KEY_EXPIRED`；卡死 IN_FLIGHT 原子接管；
- 存储读不可用时 fail-closed（503，无副作用）；
- 下游超时（504，uncertain）→ 幂等 `UNCERTAIN`（202）→ 重复只重放不重试；
- **对账回写闭环**：`UNCERTAIN` 回写确认成功 / 未成功后，同键提交直接重放稳定终态；
  遗留老结构未知态记录可正常回写与重放；
- **并发回写收敛**：16 线程同结论并发回写恰好 1 个生效，其余幂等拿到已生效结论；
  矛盾结论并发 / 顺序回写只有一个生效，落败方得到 `RECONCILE_CONFLICT` 且能看到胜出方；
- **回写拒绝可区分**：键不存在（404）/ 键过期（410）/ 非待对账（409）/ 结论冲突（409）；
  已成功、已失败记录不可被回写改掉；
- **积压上限降级**：待对账到上限后新键被 `RECONCILE_BACKLOG_FULL`（503）明确拒绝且不留记录，
  已有键重放与回写不受影响，消化积压后自动恢复；积压量经
  `GET /commands/reconciliations/backlog` 可观测，口径可配置；
- 业务校验失败 / 下游余额不足（422 FAILED）与下游失败明确区分，且稳定重放；
- 熔断：失败率打至 OPEN 快速失败 → 冷却 → 试探成功恢复 / 试探失败重新打开；
- 重试成功、重试耗尽（`RETRY_EXHAUSTED`）、熔断 / 隔离类失败不重试；
- bulkhead 满载快速失败，且依赖之间隔离互不影响；
- 日志与响应中不出现凭据原文（已断言并人工核对输出）。

每个测试用例通过 `System.out` 打印「输入 / 判定依据 / 状态迁移」，并配合含 `[traceId]`
的应用日志，便于复盘。

---

## 8. 主要代码结构

```
idempotency/    幂等入口：状态机、原子占位存储、拒绝码、不确定标记、积压上限降级
reconciliation/ 对账回写：权威结论补写、并发胜负判定、回写审计元数据、积压观测
resilience/     降级链路：CircuitBreaker / Bulkhead / TimeoutGuard / ResilientCaller
demo/           示例业务：转账服务 + 本地（可替换/可 Mock）下游客户端
trace/          MDC traceId 上下文与 Servlet 过滤器
log/            敏感信息脱敏
web/            HTTP 入口与全局异常映射
```
