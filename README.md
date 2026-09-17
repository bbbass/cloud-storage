# 云盘与相册备份系统（cloud-storage）

面向「多设备拍照后自动备份」场景的云盘与相册后端，提供大文件分片上传、秒传去重、断点续传、异步图片处理与配额计量能力。

后端是 Spring Boot 3.5 多模块工程，对象数据存放在 MinIO，缓存与限流依赖 Redis，异步处理任务经 RocketMQ 流转；仓库内附带一个 Vue 3 单页演示台，用于演示和手工验证主链路。

## 目录

- [1. 系统架构](#1-系统架构)
- [2. 关键实现](#2-关键实现)
- [3. 快速开始](#3-快速开始)
- [4. 接口文档](#4-接口文档)
- [5. 配置项参考](#5-配置项参考)
- [6. 验证与实测数据](#6-验证与实测数据)
- [7. 前端演示台](#7-前端演示台)
- [8. 常见问题](#8-常见问题)

---

## 1. 系统架构

### 1.1 模块划分与依赖方向

工程由四个 Maven 模块与一个独立前端工程组成，依赖方向严格单向：`web → service → domain → common`。

```
cloud-storage/
├── pom.xml                       # 父 POM（聚合 + dependencyManagement）
├── sql/schema.sql                # 建表脚本（utf8mb4，表名全小写）
├── scripts/
│   ├── dev-up.ps1                # 本地环境一键拉起与自检
│   └── smoke-upload.ps1          # 端到端验收脚本（16 个断言环节）
├── cloud-storage-common/         # Result<T> / ErrorCode / BusinessException / RateLimitException / UserContext / 常量
├── cloud-storage-domain/         # 实体 + Mapper（原子 SQL 与聚合查询写在 Mapper 注解里）
├── cloud-storage-service/        # 业务实现：storage / upload / limiter / quota / cache / mq / processor
├── cloud-storage-web/            # Controller / SSE / 拦截器 / 全局异常 / 启动类 / application.yml
└── cloud-storage-frontend/       # Vue 3 + Vite 演示台（npm 管理，不参与 Maven 构建）
```

模块职责边界：

| 模块 | 边界约定 |
|---|---|
| `common` | 统一响应体 `Result<T>`、错误码枚举 `ErrorCode`、业务异常、用户上下文 `UserContext`（ThreadLocal 承载 `userId` / `deviceId`），不依赖任何业务模块 |
| `domain` | 实体（`@TableName` 映射表名）与 Mapper。引用计数增减、状态 CAS、配额汇总等**原子 SQL 一律写在 Mapper 上**，不在 Service 里"先查后改" |
| `service` | 全部业务实现。不依赖 Spring MVC（禁止出现 `HttpServletRequest`），需要用户信息时从 `UserContext` 取；通过 `web` 层注册的 SSE 通道回推进度 |
| `web` | Controller、SSE、拦截器、全局异常处理、启动类与配置文件。Controller 只做参数承载与调用，不写业务逻辑 |

统一响应 `Result<T>`，业务失败抛 `BusinessException(ErrorCode)` 由全局异常兜底；触发限流抛 `RateLimitException`，在全局异常中转为 HTTP 429 并附带 `Retry-After`。

### 1.2 技术栈

| 层次 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 应用框架 | Spring Boot 3.5.14、MyBatis-Plus 3.5.14 |
| 元数据存储 | MySQL（`utf8mb4`、`lower_case_table_names=1`，表名全小写） |
| 对象存储 | MinIO（`minio-java` 8.5.17） |
| 缓存与并发控制 | Redis 6.0（ZSET 滑动窗口限流、分布式锁、配额、元数据缓存） |
| 消息队列 | RocketMQ 5.3.1（使用 `rocketmq-client` 5.0.0 封装生产者与消费者） |
| 图片处理 | Thumbnailator（缩略图）、metadata-extractor（EXIF） |
| 前端 | Vue 3 + Vite 5 + Element Plus + Axios |
| 构建 | Maven 3.9（多模块聚合）、npm |

限流基于 Redis ZSET + Lua 实现，分布式锁基于 `SET NX PX` + Lua 释放实现，消息队列使用 `rocketmq-client` 封装生产者与消费者，三者均未引入 Sentinel、Redisson、`rocketmq-spring-boot-starter` 等封装组件。

### 1.3 运行时拓扑

```
   Browser ─────────►  cloud-storage-frontend（Vue 3 + Vite，:5173）
                       │  /api/v1/**  dev 由 Vite 代理到 8080
                       ▼
                      cloud-storage-web（Controller / SSE / 拦截器 / 全局异常，:8080）
                       │
                       ▼
                      cloud-storage-service（上传 / 限流 / 配额 / 缓存 / MQ / 处理器）
                       │
        ┌──────────────┼──────────────┬───────────────┐
        ▼              ▼              ▼               ▼
      MinIO         Redis         RocketMQ         MySQL
   对象存储     缓存/限流/ZSET   异步任务流转     元数据与配额事实源
```

### 1.4 数据模型

六张表承载全部状态，元数据与配额的事实源在 MySQL，Redis 只做加速与预占。

| 表 | 关键字段 | 设计要点 |
|---|---|---|
| `file_objects` | `object_key`、`sha256`、`size`、`content_type`、`ref_count`、`status` | 物理对象表。`uk(sha256, size)` 内容寻址，多用户与多文件复用同一份数据；`ref_count` 决定物理删除时机；该表不做逻辑删除 |
| `files` | `user_id`、`object_id`、`file_name`、`parent_id`、`size`、`sha256`、`content_type` | 用户文件条目，多条记录可指向同一个 `object_id`（秒传与去重的落点） |
| `upload_sessions` | `user_id`、`device_id`、`file_name`、`file_size`、`chunk_size`、`chunk_total`、`sha256`、`status`、`object_id`、`file_id`、`expire_time` | 上传状态机，`status ∈ {INIT, UPLOADING, MERGING, DONE, FAILED, EXPIRED}`；`expire_time` 驱动过期回收 |
| `upload_parts` | `upload_id`、`part_no`、`part_size`、`sha256`、`etag` | 分片明细，`uk(upload_id, part_no)` 保证同一分片号重复上传是幂等覆盖 |
| `quota_usage` | `user_id`、`quota_bytes`、`used_bytes` | `uk(user_id)` 一行一用户；`used_bytes` 由 `files` 汇总校准 |
| `processing_tasks` | `file_id`、`object_id`、`task_type`、`status`、`retry_count`、`next_retry_time`、`result`、`error_msg`、`cost_ms` | 异步任务表，同时作为本地消息表；`uk(file_id, task_type)` 保证同一文件同一类型只存在一份任务 |

对象在存储桶中的 key 布局：

```
objects/{sha256 前 2 位}/{sha256}                # 合并产物，内容寻址，天然去重
staging/{uploadId}/{partNo}                     # 上传中的分片，合并后清理
thumbnails/{sha256 前 2 位}/{sha256}_256.jpg    # 异步生成的缩略图
```

### 1.5 Redis 数据布局

所有 key 统一使用 `cs:` 前缀，并且只使用 db1（与其他项目共用实例但分库隔离）：

| key | 结构 | 用途 | TTL |
|---|---|---|---|
| `cs:rl:{场景}:{维度}:{主体}` | ZSET | 滑动窗口限流，member 为请求标识、score 为毫秒时间戳 | 窗口 + 1s |
| `cs:quota:used:{userId}` | String | 用户已用配额（含已提交用量与进行中会话的预占） | 由对账持续收敛 |
| `cs:filemeta:{fileId}` | String(JSON) | 文件元数据缓存；DB 中不存在的 id 写入 `__NULL__` 标记 | 10 分钟 + 随机抖动；空值 30s |
| `cs:filemeta:{fileId}:lock` | String | 元数据互斥重建锁 | 5s |
| `cs:lock:*` | String | 通用分布式锁，如 `cs:lock:quota:reconcile`、`cs:lock:mq:reconcile` | 按业务给定 |

### 1.6 上传链路

```
Client                     web / service                          MinIO / MySQL / Redis
  │  POST /uploads            │
  │  {fileName,fileSize,      │  1. 秒传检查：uk(sha256,size) 命中则直接返回 fileId
  │   sha256,chunkSize}       │  2. 配额预占：Lua 原子完成 读 + 判断 + 累加
  │──────────────────────────►│  3. 落 upload_sessions（状态 INIT，写 expire_time）
  │◄── uploadId + 已传分片     │
  │                           │
  │  PUT /uploads/{id}/        │  4. 分片走原始字节流；uk(upload_id,part_no) 幂等覆盖
  │      parts/{no}           │     分片对象写入 staging/{uploadId}/{partNo}
  │──────────────────────────►│
  │  GET /uploads/{id}/progress│  5. SSE 推送进度事件（service 发布 → web 转发）
  │◄──── event: progress ─────│
  │                           │
  │  POST /uploads/{id}/       │  6. CAS 抢占 INIT/UPLOADING → MERGING，保证只合并一次
  │       complete            │  7. MinIO 服务端 compose 合并（不持数据库事务）
  │──────────────────────────►│  8. 回读对象计算 sha256 校验
  │                           │  9. 短事务：落 file_objects + files、引用计数 +1、配额落账、会话置 DONE
  │◄── fileId/objectId/costMs │ 10. 投递 THUMBNAIL / EXIF 异步任务
```

### 1.7 异步处理链路

```
上传完成 ──► 写 processing_tasks(PENDING) ──► 发布 MQ 消息 ──► RocketMQ
                                                              │
                              ┌───────────────────────────────┘
                              ▼
                     消费者：解析消息 → 取 taskId → 执行器
                              │
                    CAS: PENDING → RUNNING（抢不到即跳过）
                              │
                    查 files / file_objects 组装上下文
                              │
                      按 taskType 取处理器执行
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
        成功：DONE + result + cost_ms    失败：retry_count + 1
                                               │
                              ┌────────────────┴─────────────────┐
                              ▼                                  ▼
                   未超上限：按延迟等级重投              超过上限：置 DEAD
                                                               │
                                     POST /tasks/{id}/replay 恢复为 PENDING 并重投

补偿任务（定时 + 分布式锁单飞）：重投到点未执行的 PENDING；重置长时间卡住的 RUNNING
```

---

## 2. 关键实现

### 2.1 分片上传与合并

**分片接收**：分片接口直接接收原始字节流（`application/octet-stream`），不套用 multipart，省去一次落盘拷贝。分片长度由查询参数 `size` 显式指定，缺省时取 `Content-Length`。

**参数校验与归一**：`sha256` 必须匹配 `^[0-9a-f]{64}$`；`chunkSize` 归一到 `[5 MiB, 64 MiB]`（默认 5 MiB）；分片序号必须在 `1..chunkTotal` 范围内；分片总数上限 10000。每片的预期长度由会话推导——非末片固定为 `chunkSize`，末片为 `fileSize - chunkSize × (chunkTotal - 1)`。

**幂等写入**：分片信息落 `upload_parts`，靠 `uk(upload_id, part_no)` 保证同一分片号重复上传为覆盖写，结果幂等；分片对象写入 `staging/{uploadId}/{partNo}`。

**合并只执行一次**：`complete` 的第一步是条件更新 CAS，抢占成功的请求负责合并：

```sql
UPDATE upload_sessions SET status = 'MERGING', update_time = NOW(3)
WHERE id = #{id} AND user_id = #{userId} AND status IN ('INIT', 'UPLOADING') AND deleted = 0
```

抢占失败的请求直接读取会话已写入的 `file_id` / `object_id` 返回，因此重复调用 `complete` 得到同一结果。

**合并方式**：调用 MinIO 的**服务端 compose**，把 staging 中的分片对象在存储侧拼接为一个对象，应用侧不下载、不拼接字节。

**不持事务做外部 IO**：compose 属于外部 IO，可能持续秒级，因此合并过程不持有数据库事务，只有最后的登记动作走一段短事务（落 `file_objects`、`files`、引用计数、配额落账、会话置 `DONE`）。

**回读校验**：合并完成后把对象读回并计算 sha256，与会话记录的整文件哈希比对，不一致直接判定上传失败（错误码 `30004`），避免把损坏对象登记为可用文件。

### 2.2 秒传与引用计数去重

- **判定依据**：`file_objects` 上的 `uk(sha256, size)`。初始化上传时先按 `sha256 + size` 查找已就绪对象，命中即返回 `instantUpload = true`、`fileId`、`objectId`，本次传输 0 字节；同时新建一条 `files` 记录指向同一个物理对象，并对其引用计数 +1。因此多个用户上传同一份内容只占一份物理空间。
- **对象命名**：`objects/{sha256 前 2 位}/{sha256}`，用哈希前缀分散目录，避免单目录热点。
- **引用计数**：增减一律走单条原子 SQL，不做"先查后改"：

```sql
-- 增加引用
UPDATE file_objects SET ref_count = ref_count + 1, update_time = NOW(3) WHERE id = #{id}

-- 减少引用（带 ref_count > 0 条件，避免减成负数）
UPDATE file_objects SET ref_count = ref_count - 1, update_time = NOW(3) WHERE id = #{id} AND ref_count > 0

-- 引用归零后才物理删除，删除动作本身带条件兜底
DELETE FROM file_objects WHERE id = #{id} AND ref_count <= 0
```

### 2.3 断点续传与进度推送

- **续传依据**：`GET /uploads/{uploadId}` 返回会话状态与 `uploadedParts`。客户端中断后重新初始化，服务端按 `(userId, sha256, fileSize)` 查找未过期的活动会话并复用，返回已接收的分片列表，客户端只需补传缺失分片。
- **进度来源**：service 层在上传过程中向进度总线发布事件，`web` 层通过 SSE（`GET /uploads/{uploadId}/progress`）转发给浏览器，每完成一片推进一次，事件体包含 `percent`、`uploadedParts`、`totalParts`。
- **过期回收**：会话带 `expire_time`（默认 24 小时）。回收任务按时间扫描过期会话，删除 staging 中的分片对象与分片记录，把会话置为 `EXPIRED`，并释放该会话占用的配额预占。

### 2.4 滑动窗口限流

限流落在 Redis ZSET 上：member 为"时间戳 + 随机数"（避免同一毫秒内的多次请求互相覆盖导致计数偏少），score 为毫秒时间戳。判定逻辑写在 Lua 脚本中，由 Redis 单线程原子执行：

```lua
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]

-- 1. 先清掉窗口外的历史成员，窗口因此是真正滑动的
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
-- 2. 统计窗口内的请求数
local count = redis.call('ZCARD', key)
if count < limit then
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window + 1000)
    return {1, count + 1, 0}
end

-- 3. 被拒时给出"最早成员还要多久滑出窗口"，用于 Retry-After
local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local retry = window
if oldest[2] then
    retry = (tonumber(oldest[2]) + window) - now
    if retry < 0 then retry = 0 end
end
redis.call('PEXPIRE', key, window + 1000)
return {0, count, retry}
```

三个关键取舍：

1. **为什么用 ZSET 而不是计数器**：固定窗口计数器在窗口边界会瞬时放行约两倍流量，ZSET 以时间戳为 score，边界精确。
2. **为什么必须用 Lua**：清理过期成员、计数、写入三步必须原子，否则并发下先读后写会超发；放进 Lua 由 Redis 单线程执行，同时省掉 `WATCH/MULTI` 的重试逻辑。
3. **内存与兜底**：每个 key 的空间复杂度是 O(limit)，配合 `ZREMRANGEBYSCORE` 清理与"窗口 + 1s"的 key 过期控制内存；脚本返回异常时放行并记录告警，限流组件故障不阻断主业务。

### 2.5 限流接入方式

限流通过注解与切面接入，业务方法只声明场景：

```java
@RateLimit(scene = RateLimitScene.FILE_META)
public FileMeta get(Long userId, Long fileId) { ... }
```

切面把场景展开为**用户 + 设备两个维度**分别判定，两个维度都通过才执行目标方法；任一维度被拒立即抛出 `RateLimitException`。key 由场景与维度共同拼装，不同场景、不同维度的窗口互不影响：

```
cs:rl:{场景}:{维度}:{主体}
例如 cs:rl:upload-init:device:demo-device
```

各场景阈值由配置驱动（见 [5. 配置项参考](#5-配置项参考)），默认值：

| 场景 | 用户维度 | 设备维度 | 窗口 |
|---|---|---|---|
| `upload-init` | 60 | 20 | 1 分钟 |
| `upload-part` | 1200 | 300 | 1 分钟 |
| `upload-complete` | 60 | 20 | 1 分钟 |

### 2.6 分布式锁

用于元数据缓存重建、配额对账、任务补偿等需要"同一时刻只有一个执行者"的短任务。

- **加锁**：`SET key token NX PX ttl`，一条命令同时完成"不存在才写入"与"设置过期"，避免 `SETNX` + `EXPIRE` 两步在第二步失败时留下死锁。
- **解锁**：必须"比对 token 再删除"，否则持有者因超时失去锁后可能删掉他人的锁：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
```

- **调用形式**：除 `tryLock` / `unlock` 外提供 `execute(key, ttl, supplier)`，未抢到锁返回 `Optional.empty()`，调用方直接跳过，天然实现"单飞"。
- **TTL 策略**：固定 TTL、不自动续期，因此面向短任务设计；锁超时时间按各任务的预期执行时间给出（元数据重建 5 秒、任务补偿 2 分钟等）。

### 2.7 配额预占、落账与对账

配额需要同时满足"并发不超卖"与"Redis 与 DB 最终一致"，因此拆成三段：

**预占（Redis 原子）**：初始化上传时按整文件大小预占，Lua 在 Redis 内完成"读 + 判断 + 累加"，避免 check-then-act 在并发下超卖：

```lua
local used = tonumber(redis.call('GET', KEYS[1]) or '0')
local size = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
if used + size > limit then
    return {0, used}
end
local newUsed = redis.call('INCRBY', KEYS[1], size)
return {1, newUsed}
```

超额请求在**零字节上传**阶段即被拒绝（错误码 `50002`），不会产生任何对象与分片。

**落账（DB 为事实源）**：合并成功的短事务里对 `quota_usage.used_bytes` 做累加；会话中止或过期时释放对应的预占。释放后 Redis 计数若为负数，说明存在重复释放或数据丢失，直接归零并由对账修正。

**对账（收敛漂移）**：定时任务用分布式锁保证同一时刻只有一个实例执行，分两步完成：

1. 按 `files` 汇总重算 `quota_usage.used_bytes`，修正 DB 侧漂移；
2. 以"DB 已用 + `SUM(进行中会话.file_size)`"重算并回写 Redis。

之所以把进行中会话也计入，是因为这些字节已经在 Redis 预占、但尚未落到 `files`；只按 `files` 重算会把并发上传的预占抹掉，从而重新打开超卖的窗口。

### 2.8 文件元数据缓存

`FileMetaCache` 围绕 `cs:filemeta:{fileId}` 展开，同时处理缓存的三个经典问题：

| 问题 | 处理方式 |
|---|---|
| 穿透 | DB 中不存在的 `fileId` 写入 `__NULL__` 空值标记，TTL 仅 30 秒，避免被随机 id 持续打穿 |
| 击穿 | 未命中时先抢 `cs:filemeta:{fileId}:lock` 做互斥重建；未抢到锁的请求短暂等待后重读缓存，仍未命中才回源，保证热点 key 最多一次回源 |
| 雪崩 | 正常缓存 TTL 为 10 分钟并叠加 0 到 60 秒随机抖动，避免同一批 key 同时失效 |

另外两处处理：

- **归属校验**：缓存值与 DB 查询都带 `userId`，读取时比对归属，非本人文件按"不存在"处理，避免用他人的 `fileId` 探测数据；
- **可观测**：暴露 `hits` / `misses` / `nullHits` / `rebuilds` / `fallbackReads` / `hitRate` 指标，通过 `GET /api/v1/health` 读取，其中 `fallbackReads` 统计因未抢到重建锁而直接回源的次数。

### 2.9 异步任务流水线

**任务表即本地消息表**：`processing_tasks` 记录任务状态，`uk(file_id, task_type)` 保证同一文件的同一处理类型只有一份任务，重复投递与重复消费都不会产生第二条记录。任务状态机为 `PENDING → RUNNING → DONE`，失败累加 `retry_count` 后回到 `PENDING`（带 `next_retry_time`），超过上限转 `DEAD`。

**执行幂等**：执行器第一步做 `PENDING → RUNNING` 的条件更新 CAS，抢不到即直接返回。因此消息重复投递、补偿任务重复扫描、消费者重投都不会重复执行处理器。

**重试与死信**：失败时记录错误信息、`retry_count` 加一，并按 RocketMQ 延迟等级做退避重投；一旦超过 `max-retry` 置为 `DEAD`，由重放接口 `POST /api/v1/tasks/{taskId}/replay` 恢复为 `PENDING` 并重新投递。

**消费者始终 ack**：消费端处理完消息后统一返回 `CONSUME_SUCCESS`，即不使用 MQ 自身的重投机制，重试完全由数据库状态机驱动。这样可以避免"MQ 重投 + 业务重试"两套机制叠加导致重试次数失控、死信归属不清；消息若在 ack 前丢失，由补偿任务扫描 `PENDING` 兜底。

**补偿任务**：定时执行并用分布式锁保证单飞，做两件事——重投到点未执行的 `PENDING` 任务（覆盖消息发送失败、broker 抖动、消费者宕机）；把长时间卡在 `RUNNING` 的任务重置回 `PENDING` 并重投（覆盖进程崩溃遗留）。

### 2.10 处理处理器扩展点

处理逻辑通过统一扩展点接入，调度链路不感知具体处理类型：

```java
public interface ProcessingHandler {
    String taskType();                       // 对应 processing_tasks.task_type
    void handle(ProcessingContext context);  // 结果写回 context.setResult(...)
}
```

启动时 `ProcessingHandlerRegistry` 收集所有实现并按 `taskType` 建立索引，执行器按类型取用，未注册的类型直接报参数错误。当前有两个实现：

| 处理器 | 行为 |
|---|---|
| `ThumbnailHandler` | 从对象存储读取原图 → Thumbnailator 缩放为 256×256 → 写回 `thumbnails/{sha 前 2 位}/{sha256}_256.jpg`，任务结果记为 `{"objectKey":...,"size":...}`；非图片类型直接标记跳过 |
| `ExifHandler` | 用 metadata-extractor 解析对象流，抽取相机信息、拍摄参数、GPS 等信息并序列化为任务结果 |

处理器内部不做重试与状态流转，异常直接抛出，由执行器统一处理重试、死信与结果记录。

---

## 3. 快速开始

### 3.1 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 项目以 Java 17 编译 |
| Maven | 3.9+ | 多模块聚合构建 |
| Node.js | 18+ | 仅前端演示台需要 |
| MySQL | 8.0+ | `utf8mb4`、`lower_case_table_names=1`，表名全小写 |
| Redis | 6.0+ | 项目只使用 db1，请勿执行 `FLUSHALL` |
| MinIO | 近期版本 | bucket `cloud-storage` 会在应用启动时自动创建 |
| RocketMQ | 5.x | 需先创建 topic，5.x 默认不自动创建 |

端口：后端 `8080`、前端 `5173`、MySQL `3306`、Redis `6379`、MinIO `9000/9001`、RocketMQ `9876 / 10911`。

### 3.2 启动中间件

```bash
# MinIO（控制台 :9001）
docker run -d --name minio -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=<your-access-key> \
  -e MINIO_ROOT_PASSWORD=<your-secret-key> \
  minio/minio server /data --console-address ":9001"

# Redis（启用口令）
docker run -d --name redis -p 6379:6379 redis:6.0.16 redis-server --requirepass <your-redis-password>

# RocketMQ NameServer + Broker
docker run -d --name rocketmq-namesrv -p 9876:9876 apache/rocketmq:5.3.1 sh mqnamesrv
docker run -d --name rocketmq-broker -p 10909:10909 -p 10911:10911 -p 10912:10912 \
  -e NAMESRV_ADDR=<namesrv-host>:9876 apache/rocketmq:5.3.1 sh mqbroker
```

RocketMQ 5.x 默认 `autoCreateTopicEnable=false`，必须先创建 topic，否则客户端会持续报 `No topic route info in name server`：

```bash
docker exec rocketmq-broker sh -c \
  "cd /home/rocketmq/rocketmq-5.3.1/bin && sh mqadmin updateTopic -n <namesrv-host>:9876 -c DefaultCluster -t cloud-storage-processing -r 8 -w 8"
```

> `scripts/dev-up.ps1` 是针对 Windows + WSL 环境编写的幂等自检脚本，依次拉起 WSL、sshd、dockerd、MinIO/RocketMQ 容器、Redis 与 topic，检查 MySQL 与端口占用，最后打印 PASS/FAIL 摘要；非 Windows 环境可忽略。使用前需设置 `CS_REDIS_PASSWORD` 环境变量（未设置时跳过 Redis 检查）。

### 3.3 初始化数据库

建库与建表在同一个脚本内完成：

```bash
mysql -u root -p --default-character-set=utf8mb4 < sql/schema.sql
```

Windows PowerShell 下用重定向执行（`mysql -e "source ..."` 不可用）：

```powershell
cmd /c "mysql.exe -u root -p<password> --default-character-set=utf8mb4 < sql\schema.sql"
```

### 3.4 配置

仓库内不含真实凭据：`application.yml` 的 MinIO 凭据为环境变量占位 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`；本地配置文件 `application-dev.yml` 已被 `.gitignore` 忽略，仓库只提供模板 [`application-dev.yml.example`](cloud-storage-web/src/main/resources/application-dev.yml.example)。

首次运行先复制模板并填入本地凭据：

```bash
cp cloud-storage-web/src/main/resources/application-dev.yml.example \
   cloud-storage-web/src/main/resources/application-dev.yml
```

```powershell
copy cloud-storage-web\src\main\resources\application-dev.yml.example cloud-storage-web\src\main\resources\application-dev.yml
```

`application.yml` 默认 `spring.profiles.active=dev`，因此直接以 dev 配置启动即可；若缺少 `application-dev.yml`，启动会因数据源与 MinIO 凭据缺失而失败（凭据缺失时会给出明确提示）。

### 3.5 构建与启动后端

```bash
mvn -B -DskipTests clean package     # 打包
mvn -B test                          # 单元测试（web 模块的上下文测试需要中间件已启动）

# 启动，日志出现 Started CloudStorageApplication 即就绪
java -jar cloud-storage-web/target/cloud-storage-web.jar --spring.profiles.active=dev
```

健康检查（同时返回存储类型、MySQL、Redis 与缓存指标）：

```bash
curl -H "X-User-Id: 1" -H "X-Device-Id: demo-device" http://localhost:8080/api/v1/health
```

```json
{
  "code": 0,
  "data": {
    "app": "cloud-storage",
    "storageType": "minio",
    "db": "MySQL 9.6.0",
    "redis": "up",
    "cache": { "hits": 0, "misses": 0, "rebuilds": 0, "hitRate": 0.0 }
  },
  "success": true
}
```

### 3.6 启动前端演示台

```bash
cd cloud-storage-frontend
npm install      # Windows PowerShell 下必须使用 npm.cmd（执行策略禁用 npm.ps1）
npm run dev      # http://localhost:5173
```

Vite 已配置把 `/api` 代理到 `http://localhost:8080`，前端无需额外跨域配置。

### 3.7 端到端验收

后端启动后执行验收脚本，脚本按顺序跑完 16 个断言环节并在末行输出 `ALL ASSERTIONS PASSED`：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-upload.ps1
```

可调参数：`-BaseUrl`、`-SizeMb`、`-ChunkSize`、`-UserId`、`-DeviceId`、`-Seed`。

---

## 4. 接口文档

### 4.1 通用约定

- 路径前缀 `/api/v1`；分页参数 `page`（从 1 开始）/ `size`；时间使用 ISO-8601 格式。
- 身份通过请求头传递：`X-User-Id` + `X-Device-Id`，由 `DevAuthInterceptor` 解析并写入 `UserContext`。
- 响应统一为 `Result<T>`，`code = 0` 表示成功：

```json
{ "code": 0, "message": "成功", "data": { }, "timestamp": 1789552231614, "success": true }
```

错误码分段：10xxx 通用、20xxx 用户、30xxx 文件与上传、40xxx 相册、50xxx 限流与配额。

| code | 含义 |
|---|---|
| 10001 / 10002 / 10003 | 参数错误 / 未认证 / 无权访问 |
| 10004 / 10999 | 资源不存在 / 系统繁忙 |
| 30001 / 30002 | 文件不存在 / 上传会话不存在或已过期 |
| 30003 / 30004 | 分片缺失无法合并 / 文件校验失败（回读哈希不一致） |
| 30005 / 30006 | 分片序号越界 / 存储服务异常 |
| 30007 / 30008 | 文件正在合并中 / 当前状态不允许该操作 |
| 50001 / 50002 | 请求过于频繁（HTTP 429 + `Retry-After`）/ 存储配额不足 |

### 4.2 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/uploads` | 初始化上传；秒传命中返回 `instantUpload=true` + `fileId`，否则返回 `uploadId` 与已传分片 |
| PUT | `/api/v1/uploads/{uploadId}/parts/{partNo}` | 上传分片，原始字节流（非 multipart），同分片号可重复上传 |
| POST | `/api/v1/uploads/{uploadId}/complete` | 合并 + 回读校验 + 登记；重复调用返回同一结果 |
| GET | `/api/v1/uploads/{uploadId}` | 查询会话状态与已传分片（断点续传依据） |
| DELETE | `/api/v1/uploads/{uploadId}` | 中止上传并清理分片 |
| GET | `/api/v1/uploads/{uploadId}/progress` | SSE 进度流，事件名 `progress` |
| GET | `/api/v1/files/{fileId}` | 文件元数据（走缓存，含归属校验） |
| GET | `/api/v1/quota` | 配额：已用 / 上限 / 剩余 |
| GET | `/api/v1/tasks?fileId=` | 任务列表；不传 `fileId` 返回当前用户最近任务 |
| GET | `/api/v1/tasks/stats` | 各状态任务数 |
| POST | `/api/v1/tasks/{taskId}/replay` | 死信重放：`DEAD → PENDING` 并重新投递 |
| GET | `/api/v1/health` | 自检：存储类型 / MySQL / Redis / 缓存命中指标 |

### 4.3 调用示例

**初始化上传**

```bash
curl -X POST http://localhost:8080/api/v1/uploads \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" -H "X-Device-Id: demo-device" \
  -d '{"fileName":"photo.jpg","fileSize":12582912,"sha256":"<64-hex>","chunkSize":5242880,"contentType":"image/jpeg"}'
```

响应字段：`instantUpload`、`uploadId`、`fileId`、`objectId`、`chunkSize`、`chunkTotal`、`uploadedParts`、`expireTime`、`message`。

**上传分片**

```bash
curl -X PUT "http://localhost:8080/api/v1/uploads/{uploadId}/parts/1?size=5242880" \
  -H "Content-Type: application/octet-stream" \
  -H "X-User-Id: 1" -H "X-Device-Id: demo-device" \
  --data-binary @part-1.bin
```

响应字段：`uploadId`、`partNo`、`partSize`、`sha256`、`uploadedParts`、`totalParts`、`percent`。

> 分片接口不接受 `contentType` 查询参数：查询串中的转义斜杠（如 `application%2Foctet-stream`）会被 Tomcat 判为 400。分片 content-type 不影响最终对象，最终对象使用会话的 `contentType`。

**合并**

```bash
curl -X POST http://localhost:8080/api/v1/uploads/{uploadId}/complete \
  -H "X-User-Id: 1" -H "X-Device-Id: demo-device"
```

响应字段：`uploadId`、`fileId`、`objectId`、`sha256`、`size`、`deduped`、`costMs`（含回读校验的合并耗时）。

**SSE 进度**

```bash
curl -N -H "X-User-Id: 1" -H "X-Device-Id: demo-device" \
  "http://localhost:8080/api/v1/uploads/{uploadId}/progress"
```

```
event:progress
data:{"uploadId":123,"percent":33,"uploadedParts":1,"totalParts":3}
```

**死信重放**

```bash
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/replay \
  -H "X-User-Id: 1" -H "X-Device-Id: demo-device"
```

---

## 5. 配置项参考

### 5.1 环境变量

| 变量 | 用途 |
|---|---|
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | MinIO 凭据，对应 `application.yml` 中的占位默认值 |
| `CS_REDIS_PASSWORD` | `scripts/dev-up.ps1` 用于校验并拉起 Redis |

### 5.2 上传与存储

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `app.storage.type` | `minio` | `minio` 走对象存储；`local` 使用本地目录，便于单机调试 |
| `app.storage.local-path` | `./data/storage` | `local` 模式的存储根目录 |
| `app.storage.minio.endpoint` | `http://localhost:9000` | MinIO 地址 |
| `app.storage.minio.bucket` | `cloud-storage` | 不存在时自动创建 |
| `app.upload.default-chunk-size` | `5242880`（5 MiB） | 默认分片大小 |
| `app.upload.min-chunk-size` | `5242880` | 服务端 compose 要求除末片外每片不小于 5 MiB |
| `app.upload.max-chunk-size` | `67108864`（64 MiB） | 单分片上限 |
| `app.upload.max-chunk-total` | `10000` | 分片总数上限 |
| `app.upload.verify-after-merge` | `true` | 合并后回读校验 sha256 |
| `app.upload.session-ttl` | `24h` | 上传会话有效期 |

### 5.3 限流、配额、缓存与消息

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `app.limiter.enabled` | `true` | 是否启用限流切面 |
| `app.limiter.key-prefix` | `cs:rl` | 限流 key 前缀 |
| `app.limiter.scenes.{场景}.user-limit` / `.device-limit` / `.window` | 见 [2.5](#25-限流接入方式) | 各场景的阈值与窗口 |
| `app.quota.default-quota-bytes` | `1073741824`（1 GiB） | 每用户默认配额 |
| `app.quota.key-prefix` | `cs:quota` | 配额 key 前缀 |
| `app.redis.enabled` | dev 为 `true` | 关闭时改走进程内实现，便于在没有中间件时调试 |
| `app.mq.enabled` | `true` | 关闭时改走进程内线程池投递，便于在没有中间件时调试 |
| `app.mq.name-server` | `localhost:9876` | RocketMQ NameServer 地址 |
| `app.mq.producer-group` / `consumer-group` | `cloud-storage-producer` / `cloud-storage-consumer` | 生产组与消费组 |
| `app.mq.topic` / `tag` | `cloud-storage-processing` / `processing` | 处理任务 topic 与标签 |
| `app.mq.max-retry` | `3`（dev 为 `2`） | 超过后置为 `DEAD` |
| `app.mq.retry-backoff` | `5s`（dev 为 `3s`） | 退避重投的时间基准 |
| `app.mq.consume-thread-min` / `max` | `2` / `8` | 并发消费线程数 |
| `app.mq.reconcile-interval` | `30s`（dev 为 `5s`） | 补偿任务执行间隔 |
| `app.mq.stuck-running-timeout` | `5m` | 超过该时长仍为 `RUNNING` 的任务视为卡住并重置重投 |

---

## 6. 验证与实测数据

### 6.1 自动化测试

- 单元测试共 20 个：`cloud-storage-service` 19 个（滑动窗口限流器、任务执行器、缩略图与 EXIF 处理器、本地存储实现、上传服务）+ `cloud-storage-web` 上下文测试 1 个，`mvn -B test` 全部通过。
- 端到端脚本 `scripts/smoke-upload.ps1` 共 16 个环节，覆盖分片上传、合并幂等、秒传、断点续传、SSE 进度、配额超限、限流 429、元数据缓存、MQ 流水线、死信重放与任务统计。

### 6.2 实测结果

| 指标 | 实测结果 |
|---|---|
| 分片上传 | 12 MiB 文件按 5 + 5 + 2 MiB 三片上传并合并成功 |
| 合并耗时 | 含回读 sha256 校验：1.86 s / 1.90 s（两次运行） |
| 秒传 | 同内容重复上传命中 `instantUpload=true`，传输 0 字节；生成新 `fileId`、复用同一 `objectId`，`ref_count` 正确累加 |
| 合并幂等 | 重复调用 `complete` 返回同一 `fileId` |
| 断点续传 | 仅上传第 1 片后重新初始化，返回 `uploadedParts=[1]` 并复用同一 `uploadId` |
| SSE 进度 | 依次收到 33% → 67% → 100% 三个进度事件 |
| 限流精度 | 设备阈值 20 次/分钟：第 21 次请求被拒，返回 HTTP 429 与 `Retry-After: 59` |
| 配额超限 | 1 GiB 配额下预占 2 GiB，返回错误码 `50002`，且未产生任何对象 |
| 配额对账 | 定时对账处理 2 个用户，`cs:quota:used:1` 为 176160768 字节，与数据库汇总一致 |
| 元数据缓存 | 冷启动连续两次读取：`hits=1`、`misses=1`、`rebuilds=1`，第二次读取由缓存返回 |
| 异步处理 | 上传 1 张 JPEG 后自动产生 THUMBNAIL 与 EXIF 两条任务并执行完成；缩略图 `thumbnails/{前缀}/{sha256}_256.jpg`（1762 字节）写入 MinIO |
| 死信与重放 | 损坏图片重试耗尽后进入 `DEAD`（`errorMsg=生成缩略图失败：No suitable ImageReader found`），调用重放接口后恢复为 `PENDING` |
| 消息积压 | 10 条消息、8 个队列，消费位点与 broker 位点差值全部为 0 |

---

## 7. 前端演示台

前端用于演示和手工验证后端主链路，页面结构与职责：

```
cloud-storage-frontend/
├── src/App.vue            # 顶部状态条 + 路由出口
├── src/views/HomeView.vue # 演示工作区（上传面板 / 任务面板）
├── src/api/http.js        # 统一响应解包、身份请求头注入、429 处理、雪花 ID 精度兼容
├── src/api/demo.js        # 健康 / 配额 / 上传 / 任务接口封装
└── src/utils/format.js    # 字节、百分比、时间格式化
```

**顶部状态条**：健康状态标签（存储类型 / DB / Redis）、可编辑的用户 ID 与设备 ID（保存在 `localStorage`，作为 `X-User-Id` 与 `X-Device-Id` 注入所有请求，包括 SSE 连接）、配额进度条，以及手动刷新按钮。

**上传面板**：点击或拖拽选择文件（演示用文件限制 128 MiB）→ 浏览器计算 sha256 → 初始化（展示是否命中秒传）→ 逐片上传并展示进度 → 合并；支持暂停与继续（验证断点续传）、取消（中止会话）、订阅 SSE 展示服务端进度；被限流时展示错误信息与 `Retry-After`；面板底部提供调试信息折叠区，展示 `uploadId` / `fileId` / `objectId` 等关键返回。

**任务面板**：任务状态统计（`PENDING` / `RUNNING` / `DONE` / `DEAD`）、最近任务列表（类型、状态、文件 ID、创建时间、重试次数、结果或错误信息），`DEAD` 状态的任务提供"重放"按钮。

前端会把响应中的 `uploadId` / `fileId` / `objectId` / `taskId` 等雪花 ID 转为字符串，避免超过 JS `Number.MAX_SAFE_INTEGER` 导致精度丢失。

---

## 8. 常见问题

**启动报 MinIO 凭据未配置**
未创建本地 `application-dev.yml`，或未设置 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`。按 [3.4 配置](#34-配置) 复制模板并填入凭据。

**客户端持续报 `No topic route info in name server`**
RocketMQ 5.x 默认不自动创建 topic，需先执行 `mqadmin updateTopic`（见 [3.2 启动中间件](#32-启动中间件)）。在 broker 容器内执行 mqadmin 时，`-n` 必须指向 NameServer（例如 `rocketmq-namesrv:9876`），不能用 `127.0.0.1`，那指向的是 broker 自身。

**分片接口返回 400**
分片接口不接受 `contentType` 查询参数，查询串中的 `%2F` 会被 Tomcat 直接拒绝。请使用原始字节流并只传 `size`。

**启动日志出现 `mapper[...] is ignored`，运行期报找不到语句**
MyBatis 以方法名作为语句 id，Mapper 接口不允许同名重载方法，后定义的方法会被静默忽略。

**`npm install` / `npm run dev` 报执行策略错误（Windows）**
PowerShell 禁用了 `npm.ps1`，请使用 `npm.cmd` 与 `npx.cmd`。

**`mvn clean` 报无法删除 `cloud-storage-web.jar`**
通常是 IDE 的 Java 语言服务器正在扫描 `target/` 造成文件占用，稍后重试即可；必要时在 IDE 中排除 `target/` 目录。

**Maven 控制台中文乱码**
属于显示问题（代码页 936），执行 `chcp 65001` 后可正常显示。

**前端读取到的 `fileId` 末尾变成 0**
雪花 ID 超出 JS 安全整数范围，由前端兼容层转为字符串处理，详见 [7. 前端演示台](#7-前端演示台)。

---