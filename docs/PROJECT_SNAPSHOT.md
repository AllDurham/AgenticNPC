# AgenticNPC 项目快照

> 生成时间：2026-05-10 | 分支：master | 最新 commit：6b5fb29

---

## 1. Java 文件清单（50 个）

### 主类

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc` | `AgenticNPCPlugin` | 插件主类，负责所有组件的装配与生命周期管理 |

### config — 配置层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.config` | `ConfigManager` | 统一读取 config.yml 所有配置项，支持热重载 |
| `com.agenticnpc.config` | `BrainConfig` | 单个 NPC Brain 的不可变配置快照（record） |

### model — 数据模型层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.model` | `InteractionEvent` | 统一交互事件模型，由 Layer 1 Hook 生成 |
| `com.agenticnpc.model` | `ChatMessage` | 单条对话消息（role + content + timestamp） |
| `com.agenticnpc.model` | `LLMResponse` | LLM 返回的结构化响应（含 emotion_change） |
| `com.agenticnpc.model` | `ActionParameters` | 动作参数（物品/坐标/药水，所有字段可空） |
| `com.agenticnpc.model` | `ActionType` | 动作类型枚举（NONE/GIVE_ITEM/TELEPORT/GIVE_EFFECT） |
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
| `com.agenticnpc.dispatch` | `AsyncDispatcher` | 核心异步调度器，串联限流→Prompt→LLM→解析→校验→执行全链路 |
| `com.agenticnpc.dispatch` | `LLMClient` | LLM HTTP 客户端，使用 Java 17 内置 HttpClient |
| `com.agenticnpc.dispatch` | `CircuitBreaker` | 熔断器（CLOSED→OPEN→HALF_OPEN 状态机） |
| `com.agenticnpc.dispatch` | `RateLimiter` | 令牌桶限流器，按玩家+Brain 双维度限流 |
| `com.agenticnpc.dispatch` | `LLMException` | LLM 通信异常，由熔断器捕获计入失败次数 |

### gateway — 安全网关层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.gateway` | `InputSanitizer` | 输入清洗器：长度截断 + 注入模式匹配 + 标签逃逸剥离 + Unicode 规范化 |
| `com.agenticnpc.gateway` | `ActionValidator` | 动作校验器：检查 action_type 是否在 Brain 白名单内 |
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
| `com.agenticnpc.executor` | `ActionExecutor` | 动作执行器：台词渲染（聊天框+ActionBar+音效）+ 物品给予 + 传送 + 药水效果 |

### context — 上下文构建层

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.context` | `PromptBuilder` | Prompt 构建器：组装 System Prompt（人格+画像+摘要+情绪+玩家状态+格式约束） |

### memory — 记忆系统

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.agenticnpc.memory` | `MemoryRepository` | 持久化接口（历史/摘要/画像/情绪） |
| `com.agenticnpc.memory` | `SQLiteRepository` | SQLite 实现：6 张表 + WAL 模式 + HikariCP 连接池 |
| `com.agenticnpc.memory` | `MemoryManager` | 记忆管理器：L1 Guava Cache + L2 SQLite，含压缩触发逻辑 |
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
| `com.agenticnpc.audit` | `AuditLogger` | 异步文件轮转审计日志（按日期切割，10000 条队列） |
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
| `com.agenticnpc.command` | `BindCommand` | /anpc 主命令（bind/unbind/status/reload/stats），含 Tab 补全 |
| `com.agenticnpc.command` | `BindPendingListener` | 监听右键事件完成 bind/unbind/status 的实体选择 |
| `com.agenticnpc.command` | `StatsCommand` | /anpc stats 子命令，查询 Token 消耗统计 |

---

## 2. config.yml 完整内容

```yaml
# ================================================================
# AgenticNPC 配置文件 v1.1
# ================================================================

# ---- LLM API 配置 ----
llm:
  endpoint: "https://api.deepseek.com/chat/completions"
  api-key: "sk-在这里填写你的API密钥"
  model: "deepseek-v4-flash"
  temperature: 0.8
  max-tokens: 1024
  timeout:
    connect-ms: 3000
    read-ms: 8000

# ---- 限流配置 ----
rate-limit:
  global-per-player: false
  max-requests: 5
  period-ms: 60000

# ---- 熔断器配置 ----
circuit-breaker:
  failure-threshold: 5
  recovery-time-ms: 30000

# ---- 记忆配置 ----
memory:
  ttl-minutes: 5
  max-sessions: 500
  max-history-per-session: 10

# ---- 上下文配置 ----
context:
  inventory-enabled: true

# ---- 渲染配置 ----
render:
  action-bar-enabled: true
  sound-enabled: true

# ---- 降级话术 ----
fallback:
  dialogue: "魔法波动过于剧烈，NPC 陷入了沉思..."

# ---- 数据库配置 ----
database:
  backend: "sqlite"
  sqlite:
    file: "memories.db"
  mysql:
    host: "localhost"
    port: 3306
    database: "agentic_npc"
    username: "root"
    password: "your_password"

# ---- 传送守卫（WorldGuard 领地限制）----
teleport-guard:
  enabled: false
  mode: "blacklist"
  regions:
    - "spawn"
    - "pvp_arena"

# ---- 压缩配置 ----
compression:
  exit-threshold: 5
  active-threshold: 10
  max-summary-tokens: 1024
  model: ""
  endpoint: ""
  api-key: ""

# ---- 画像配置 ----
profile:
  max-tokens: 512

# ---- 情绪系统 ----
emotion:
  enabled: true

# ---- 服务器标识 ----
server-id: "survival-1"

# ---- Token 告警配置 ----
token-alert:
  single-request-threshold: 800
  daily-player-threshold: 10000

# ---- 审计日志配置 ----
audit:
  enabled: true
  retention-days: 30

# ---- 调试模式 ----
debug: true

# ================================================================
# NPC Brain 配置
# ================================================================
brains:
  - id: "default_npc"
    name: "艾尔文"
    personality: >
      你是一个见多识广的神秘旅行者，走遍了这片大陆的每个角落。
      你性格内敛而睿智，说话简短有深度，不轻易透露自己的过去。
      你对初次见面的冒险者保持礼貌但略显疏离，不会主动给予物品。
      只有当旅行者真正需要帮助且你认为值得时，才会慷慨解囊。
      你的名字是艾尔文，但你很少主动介绍自己，除非对方直接询问。
    fallback-dialogue: "命运的齿轮暂时停止了转动..."
    allowed-action-types: ["GIVE_ITEM", "TELEPORT", "GIVE_EFFECT"]
    allowed-items: ["BREAD", "APPLE", "DIAMOND"]
    allowed-potion-effects: ["HEAL", "REGENERATION", "SPEED", "NIGHT_VISION"]
    dialogue-sound: "ENTITY_VILLAGER_AMBIENT"
    dialogue-sound-volume: 1.0
    dialogue-sound-pitch: 1.0
    action-bar-enabled: true
    profile-scope: "brain"
    emotion-shared: false
    default-emotion: "NEUTRAL"

  - id: "healer"
    name: "梅琳"
    personality: >
      你是村庄里的治疗师梅琳，温柔善良，精通草药和治愈魔法。
      你看到受伤的冒险者会心生怜悯，主动提供治疗效果。
      你不会给予物品，也不会传送他人，但你的治愈之力无人能及。
      你说话温柔，喜欢用"亲爱的"称呼对方。
    fallback-dialogue: "治愈之力暂时消散了..."
    allowed-action-types: ["GIVE_EFFECT"]
    allowed-items: []
    allowed-potion-effects: ["HEAL", "REGENERATION", "ABSORPTION"]
    dialogue-sound: "ENTITY_VILLAGER_AMBIENT"
    dialogue-sound-volume: 0.8
    dialogue-sound-pitch: 1.2
    action-bar-enabled: true
    profile-scope: "brain"
    emotion-shared: false
    default-emotion: "FRIENDLY"

  - id: "teleporter"
    name: "奥利安"
    personality: >
      你是掌握空间魔法的传送师奥利安，性格高傲但实力强大。
      你可以在瞬间将人送到大陆的任何角落，但你需要对方明确说出目的地。
      你从不白干活——除非对方表现出足够的诚意或有趣的灵魂。
      你说话带点神秘感，喜欢用"空间的涟漪"之类的比喻。
    fallback-dialogue: "空间的涟漪暂时紊乱了..."
    allowed-action-types: ["TELEPORT"]
    allowed-items: []
    allowed-potion-effects: []
    dialogue-sound: "ENTITY_ENDER_DRAGON_FLAP"
    dialogue-sound-volume: 0.5
    dialogue-sound-pitch: 1.5
    action-bar-enabled: true
    profile-scope: "brain"
    emotion-shared: true
    default-emotion: "WARY"
```

---

## 3. 已完成 Sprint 清单

### Sprint 1：生态打通与动作扩容

| 任务 | 状态 | 说明 |
|------|------|------|
| Citizens Hook | ✅ 完成 | 监听 NPCRightClickEvent |
| MythicMobs Hook | ✅ 完成 | 监听 MythicMobInteractEvent |
| TELEPORT 动作 | ✅ 完成 | Y 坐标安全检查 + WorldGuard 领地检查 |
| GIVE_EFFECT 动作 | ✅ 完成 | 药水白名单 + 时长/等级截断 |
| PromptBuilder 动态格式 | ✅ 完成 | 仅输出 config 中允许的动作类型 |
| WorldGuard 集成 | ✅ 完成 | TeleportGuard 领地白名单/黑名单 |
| 药水守卫 | ✅ 完成 | PotionGuard 效果白名单 |
| 多 NPC 配置 | ✅ 完成 | 艾尔文/梅琳/奥利安三个角色 |

### Sprint 2：工程完善

| 任务 | 状态 | 说明 |
|------|------|------|
| 审计日志系统 | ✅ 完成 | AuditLogger 异步文件轮转 |
| MySQL 审计双写 | ✅ 完成 | MySQLAuditRepository |
| Token 统计系统 | ✅ 完成 | TokenTracker 异步统计 + 告警 |
| LLMRawResult | ✅ 完成 | LLMClient 返回 content + 完整响应体 |
| Prompt 注入防御升级 | ✅ 完成 | XML 标签隔离 + 闭合符剥离 |
| /anpc stats 命令 | ✅ 完成 | 全局/玩家/Brain 三维度查询 |
| Tab 补全 | ✅ 完成 | stats 子命令完整补全 |
| 服务器标识 | ✅ 完成 | server-id 配置 |

### Sprint 3：记忆与人格

| 任务 | 优先级 | 状态 | 说明 |
|------|--------|------|------|
| S3-F1 摘要压缩系统 | P0 | ✅ 完成 | CompressionService + 主动/退出触发 |
| S3-F2 永久画像系统 | P0 | ✅ 完成 | PlayerProfileManager + 管理员命令 |
| S3-F4 情绪值系统 | P0 | ✅ 完成 | 5 档枚举 + 自动升降 + 共享/独立模式 |
| S3-E1 热重载 | P1 | ✅ 完成 | LLMClient 重建 + 熔断器重置 + 画像缓存清空 |
| S3-E2 日志清理 | P1 | ⚠️ 部分完成 | ConfigManager 已有配置项，AuditLogger 定时清理任务未接入主类 |
| S3-F3 profile-scope | P1 | ✅ 完成 | BrainConfig 新增 profile-scope 字段 |
| S3-D1 用户文档 | P2 | ✅ 完成 | docs/USER_GUIDE.md |
| S3-D2 开发者文档 | P2 | ✅ 完成 | docs/DEVELOPER_GUIDE.md |

---

## 4. Sprint 3 完成状态总结

### 已完成（100%）

- ✅ 摘要压缩系统（CompressionService + LLMClient.sendRawAsync + MemoryManager 触发逻辑）
- ✅ 永久画像系统（PlayerProfileManager + /anpc profile 命令 + PromptBuilder 注入）
- ✅ 情绪值系统（EmotionLevel + EmotionManager + LLMResponse.emotion_change + PromptBuilder 注入）
- ✅ 热重载（CircuitBreaker.reset + AsyncDispatcher.setLLMClient + BindCommand.handleReload 完整链路）
- ✅ profile-scope 配置（BrainConfig + ConfigManager + config.yml）
- ✅ 用户文档（USER_GUIDE.md）
- ✅ 开发者文档（DEVELOPER_GUIDE.md）

### 未完成

- ⚠️ **S3-E2 审计日志自动清理**：ConfigManager 已有 `isAuditAutoCleanup()` 和 `getAuditRetentionDays()` 配置项，AuditLogger 的 `startCleanupTask()` 方法已在任务书中定义但未写入代码。需要在主类 `onEnable` 中调用 `auditLogger.startCleanupTask(this)` 激活。

---

## 5. v1.2 路线图待办事项

### 功能层面
- [ ] Citizens / MythicMobs 深度集成（NPC 名称显示、皮肤同步）
- [ ] MySQL 后端完整实现（MySQLRepository）
- [ ] 更多 ActionType（SEND_TITLE、PLAY_SOUND、GIVE_XP）
- [ ] `/anpc emotion` 管理员命令（手动设置情绪档位）
- [ ] `/anpc profile` Tab 补全
- [ ] 多语言支持（i18n）
- [ ] 审计日志自动清理定时任务接入主类

### 安全层面
- [ ] 语义级 Prompt 注入防御（基于 LLM 自检）
- [ ] 审计日志完整规范（轮转 / 脱敏 / 异步写入）
- [ ] 审计日志 Web 查看面板

### 工程层面
- [ ] Redis 跨服限流（严格零误差，替代当前 MySQL 30s 同步方案）
- [ ] BungeeCord 会话同步
- [ ] Token 用量统计 Web 面板
- [ ] Prompt 模板版本迁移策略
- [ ] 单元测试覆盖
- [ ] CI/CD 流水线

### 性能层面
- [ ] 压缩模型支持（可配置更便宜的模型做压缩）
- [ ] 批量 Token 统计写入
- [ ] 热缓存预热策略

---

## 6. Git 提交历史

```
6b5fb29 docs: 用户指南 + 开发者指南
4bc41ad feat(v1.1-sprint3): 摘要压缩 + 永久画像 + 情绪系统 + 热重载
e58064d fix: /anpc stats 子命令 Tab 补全
b0c177d fix(security): 剥离标签逃逸闭合符防注入突破
a48445c feat(v1.1-sprint2): 审计日志 + Token统计 + 注入防御升级
4e2fde3 feat(v1.1): WorldGuard 领地守卫 + 药水白名单 + 多 NPC 配置
981aa57 feat: v1.1 Sprint 1 - 生态打通与动作扩容
637e2a3 feat: AgenticNPC MVP 完整版 (Phase 1-5)
```
