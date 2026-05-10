# AgenticNPC 项目快照

> 生成时间：2026-05-10 | 分支：master | 最新 tag：v1.3-alpha
> 源文件：56 个 | 测试文件：6 个 | 测试用例：93 个 | JAR：23MB

---

## 1. Java 文件清单（56 个）

### 主类

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc` | `AgenticNPCPlugin` | 插件主类，负责所有组件的装配与生命周期管理 |

### config — 配置层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.config` | `ConfigManager` | 统一读取 config.yml 所有配置项，支持热重载 |
| `com.agenticnpc.config` | `BrainConfig` | 单个 NPC Brain 的不可变配置快照（record），含 sound-whitelist |

### model — 数据模型层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.model` | `InteractionEvent` | 统一交互事件模型，由 Layer 1 Hook 生成 |
| `com.agenticnpc.model` | `ChatMessage` | 单条对话消息（role + content + timestamp） |
| `com.agenticnpc.model` | `LLMResponse` | LLM 返回的结构化响应（含 emotion_change） |
| `com.agenticnpc.model` | `ActionParameters` | 动作参数（18 个字段，所有字段可空） |
| `com.agenticnpc.model` | `ActionType` | 动作类型枚举（7 种：NONE/GIVE_ITEM/TELEPORT/GIVE_EFFECT/SEND_TITLE/PLAY_SOUND/GIVE_XP） |
| `com.agenticnpc.model` | `PromptPackage` | 组装完毕的 Prompt 包，交付给通信层 |

### pipeline — 管线接口

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.pipeline` | `InteractionPipeline` | 交互处理管线接口，解耦 Hook 层与处理层 |

### hook — Layer 1 接入层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.hook` | `ChatCollector` | 玩家对话状态机（LISTENING ↔ PROCESSING），拦截聊天事件 |
| `com.agenticnpc.hook` | `VanillaHook` | 原版实体交互 Hook，监听 PlayerInteractEntityEvent |
| `com.agenticnpc.hook` | `CitizensHook` | Citizens NPC 交互 Hook，监听 NPCRightClickEvent |
| `com.agenticnpc.hook` | `MythicMobsHook` | MythicMobs 交互 Hook，监听 MythicMobInteractEvent |
| `com.agenticnpc.hook` | `HookRegistry` | Hook 注册中心，运行时类加载探测后动态注册 |

### dispatch — 异步调度层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.dispatch` | `AsyncDispatcher` | 核心异步调度器，串联限流→语义审核→Prompt→LLM→解析→校验→执行全链路 |
| `com.agenticnpc.dispatch` | `LLMClient` | LLM HTTP 客户端，使用 Java 17 内置 HttpClient |
| `com.agenticnpc.dispatch` | `CircuitBreaker` | 熔断器（CLOSED→OPEN→HALF_OPEN 状态机） |
| `com.agenticnpc.dispatch` | `RateLimiter` | 限流器接口（tryAcquire + shutdown） |
| `com.agenticnpc.dispatch` | `LocalRateLimiter` | 本地 JVM 令牌桶限流器实现 |
| `com.agenticnpc.dispatch` | `RedisRateLimiter` | Redis 跨服限流器（Lua 原子固定窗口，fail-open） |
| `com.agenticnpc.dispatch` | `LLMException` | LLM 通信异常，由熔断器捕获计入失败次数 |

### gateway — 安全网关层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.gateway` | `InputSanitizer` | 输入清洗器：长度截断 + 注入模式匹配 + 标签逃逸剥离 + Unicode 规范化 |
| `com.agenticnpc.gateway` | `SemanticGuard` | 语义注入防御：LLM 二次审核（SAFE/SUSPICIOUS/BLOCKED），fail-open |
| `com.agenticnpc.gateway` | `ActionValidator` | 动作校验器：白名单 + 参数完整性 + sound-whitelist + XP 有效性 |
| `com.agenticnpc.gateway` | `ItemSafetyGuard` | 物品安全守卫：Material 解析 + 白名单 + 数量截断 + 跨版本别名映射 |
| `com.agenticnpc.gateway` | `LLMResponseParser` | LLM 响应容错解析器：JSON 解析 + Markdown 剥离 + 纯文本兜底 |
| `com.agenticnpc.gateway.model` | `SanitizeResult` | 输入清洗结果（accepted/value/rejectReason） |
| `com.agenticnpc.gateway.model` | `ValidationResult` | 动作校验结果（valid/actionType/parameters/failReason） |
| `com.agenticnpc.gateway.model` | `ItemSafetyResult` | 物品安全检查结果（safe/material/amount/reason） |

### guard — 安全守卫层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.guard` | `TeleportGuard` | WorldGuard 领地白名单/黑名单传送检查 |
| `com.agenticnpc.guard` | `PotionGuard` | 药水效果白名单 + 时长/等级安全截断 |

### executor — 执行层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.executor` | `ActionExecutor` | 动作执行器：台词 + 物品 + 传送 + 药水 + 标题 + 音效 + 经验值，含审计日志 |

### context — 上下文构建层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.context` | `PromptBuilder` | Prompt 构建器：动态生成 7 种 ActionType 的格式约束 |

### memory — 记忆系统

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.memory` | `MemoryRepository` | 持久化接口（历史/摘要/画像/情绪） |
| `com.agenticnpc.memory` | `SQLiteRepository` | SQLite 实现：6 张表 + WAL 模式 + HikariCP |
| `com.agenticnpc.memory` | `MySQLRepository` | MySQL 实现：utf8mb4 + ON DUPLICATE KEY + fail-fast |
| `com.agenticnpc.memory` | `MemoryManager` | 记忆管理器：L1 Guava Cache + L2 持久化，含压缩触发 |
| `com.agenticnpc.memory` | `CompressionService` | 对话摘要压缩服务，调用 LLM 生成第三人称摘要 |
| `com.agenticnpc.memory` | `PlayerProfileManager` | 永久玩家画像管理器（管理员维护，内存缓存+持久化） |

### emotion — 情绪系统

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.emotion` | `EmotionLevel` | 情绪档位枚举（HOSTILE→WARY→NEUTRAL→FRIENDLY→DEVOTED） |
| `com.agenticnpc.emotion` | `EmotionChange` | 情绪变化枚举（NONE/UPGRADE/DOWNGRADE） |
| `com.agenticnpc.emotion` | `EmotionManager` | 情绪管理器：热缓存+持久化，支持每玩家独立或全体共享 |

### audit — 审计与统计

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.audit` | `AuditLogger` | 结构化 JSON 审计日志（JSONL 格式，按日期轮转） |
| `com.agenticnpc.audit` | `AuditRepository` | 审计持久化接口（MySQL 双写用） |
| `com.agenticnpc.audit` | `MySQLAuditRepository` | MySQL 审计双写实现 |
| `com.agenticnpc.audit` | `TokenTracker` | Token 用量异步统计 + 告警，支持全局/玩家/Brain 三维度查询 |

### storage — 实体绑定存储

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.storage` | `EntityBrainStorage` | 实体与 Brain ID 的绑定存储接口 |
| `com.agenticnpc.storage` | `PersistentDataStorage` | 1.14+ 实现（PersistentDataContainer，重启不丢失） |
| `com.agenticnpc.storage` | `MetadataStorage` | 1.12-1.13 兼容实现（Metadata API） |
| `com.agenticnpc.storage` | `EntityBrainStorageFactory` | 运行时版本探测，自动选择存储实现 |

### command — 命令系统

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.command` | `BindCommand` | /anpc 主命令路由（bind/unbind/status/reload/stats/emotion/health），含 Tab 补全 |
| `com.agenticnpc.command` | `BindPendingListener` | 监听右键事件完成 bind/unbind/status 的实体选择 |
| `com.agenticnpc.command` | `StatsCommand` | /anpc stats 子命令，查询 Token 消耗统计 |
| `com.agenticnpc.command` | `EmotionCommand` | /anpc emotion 子命令，管理员手动设置情绪档位 |
| `com.agenticnpc.command` | `HealthCommand` | /anpc health 子命令，运行状态观测（熔断/限流/Redis/Token/错误） |

---

## 2. 测试文件清单（6 个，93 个测试用例）

| 测试类 | 用例数 | 覆盖范围 |
|--------|--------|----------|
| `ActionValidatorTest` | 14 | 白名单拦截、参数校验、新 ActionType、sound-whitelist、GIVE_XP |
| `InputSanitizerTest` | 29 | 空输入、长度截断、注入模式（6 类）、标签逃逸、Unicode NFKC、控制字符 |
| `LLMResponseParserTest` | 17 | 标准 JSON、Markdown 剥离、纯文本兜底、新动作解析、emotion_change |
| `PromptRegressionTest` | 14 | JSON fixture 驱动回归：所有 ActionType + 边界 + Markdown + emotion |
| `SemanticGuardTest` | 16 | SAFE/SUSPICIOUS/BLOCKED 判定、额外文字容忍、fail-open（5 种异常场景） |
| `HealthCommandTest` | 3 | 错误计数器、SemanticGuard 标记 |

---

## 3. 已完成 Sprint 清单

### Sprint 1：生态打通与动作扩容 ✅

Citizens/MythicMobs Hook、TELEPORT/GIVE_EFFECT 动作、WorldGuard 集成、药水守卫、多 NPC 配置

### Sprint 2：工程完善 ✅

审计日志系统、MySQL 审计双写、Token 统计、Prompt 注入防御升级、/anpc stats 命令、Tab 补全

### Sprint 3：记忆与人格 ✅

摘要压缩、永久画像、情绪值系统、热重载、审计日志自动清理（S4-E1 补完）

### Sprint 4：动作扩容 + MySQL 后端 ✅

| 任务 | 说明 |
|------|------|
| S4-E1 | 审计日志自动清理定时任务接入主类 |
| S4-F1 | SEND_TITLE — 游戏内标题（长度截断 + §孤立剥离 + 时长裁剪） |
| S4-F2 | PLAY_SOUND — 音效播放（sound-whitelist + 跨版本别名映射 9 条） |
| S4-F3 | GIVE_XP — 经验值给予（单次上限截断 + 审计标记原始值/实际值） |
| S4-F4 | MySQLRepository — 完整 MySQL 后端（HikariCP + utf8mb4 + fail-fast） |
| S4-F5 | /anpc emotion 管理员命令（权限 agenticnpc.admin.emotion） |
| S4-F6 | /anpc profile Tab 补全 + emotion Tab 补全 |

### Sprint 5：测试 + 安全 + 可观测性 ✅

| 任务 | 说明 |
|------|------|
| P0-1 | 单元测试体系（JUnit 5 + Mockito，74 tests） |
| P0-2 | Prompt Regression 测试（14 条 JSON fixture） |
| P0-3 | GitHub Actions CI Pipeline |
| P0-4 | Structured Audit Event（JSONL 格式） |
| P1-A | Redis 跨服限流（双模式：local/redis，Lua 原子固定窗口，fail-open） |
| P1-B | SemanticGuard 语义注入防御（独立 LLM 审核，SAFE/SUSPICIOUS/BLOCKED） |
| P1-C | /anpc health 命令（7 项运行指标） |

---

## 4. 架构师已知待改进项

| 编号 | 问题 | 当前状态 | 建议改进 |
|------|------|----------|----------|
| P1-1 | Redis 固定窗口边界突刺 | 可接受 | Sorted Set 滑动窗口 |
| P1-2 | SemanticGuard 关键词判定脆弱 | 可接受 | 强制 JSON 输出 + 结构化解析 |
| P2-1 | Redis fail-open 无熔断 | 可接受 | RedisCircuitBreaker 或 30s degrade cache |

---

## 5. 项目统计

| 指标 | 数值 |
|------|------|
| 源文件 | 56 个 Java |
| 测试文件 | 6 个 Java |
| 测试用例 | 93 个（0 failures） |
| JAR 体积 | 23 MB |
| Shade 依赖 | Gson, Guava, HikariCP, SQLite, MySQL, Jedis |
| 可选 provided | Citizens, MythicMobs, WorldGuard |
| CI | GitHub Actions (build → test → package) |

---

## 6. Git 提交历史

```
fa3be36 feat(v1.3-sprint5-p1): Redis 限流 + 语义注入防御 + Health 命令
e1524ce feat(v1.2-sprint5): 测试体系 + CI + 结构化审计
2863e63 fix(sprint4): 架构审查修复 — 审计日志 + §孤立标记
73a1891 feat(v1.2-sprint4): 新增 ActionType + MySQL 后端 + 管理员命令
9af3960 docs: 项目快照文档
6b5fb29 docs: 用户指南 + 开发者指南
4bc41ad feat(v1.1-sprint3): 摘要压缩 + 永久画像 + 情绪系统 + 热重载
e58064d fix: /anpc stats 子命令 Tab 补全
b0c177d fix(security): 剥离标签逃逸闭合符防注入突破
a48445c feat(v1.1-sprint2): 审计日志 + Token统计 + 注入防御升级
4e2fde3 feat(v1.1): WorldGuard 领地守卫 + 药水白名单 + 多 NPC 配置
981aa57 feat: v1.1 Sprint 1 - 生态打通与动作扩容
637e2a3 feat: AgenticNPC MVP 完整版 (Phase 1-5)
```
