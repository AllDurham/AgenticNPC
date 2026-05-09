# AgenticNPC 开发者指南

> 版本：v1.1 | 最后更新：2026-05-10

---

## 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                    Layer 1: Hook 层                      │
│  VanillaHook │ CitizensHook │ MythicMobsHook            │
│  监听玩家右键实体事件，标准化为 InteractionEvent          │
└──────────────────────┬──────────────────────────────────┘
                       │ InteractionEvent
                       ▼
┌─────────────────────────────────────────────────────────┐
│                Layer 2: 状态机层                         │
│                    ChatCollector                         │
│  管理玩家对话状态：LISTENING ↔ PROCESSING               │
│  拦截聊天事件，输入清洗，提交管线                         │
└──────────────────────┬──────────────────────────────────┘
                       │ InteractionEvent (已清洗)
                       ▼
┌─────────────────────────────────────────────────────────┐
│               Layer 3: 异步调度层                        │
│                  AsyncDispatcher                         │
│  限流 → Prompt构建 → 熔断器 → LLM请求 → 解析 → 校验    │
│  → 安全检查 → 记忆追加 → 审计 → Token统计               │
└──────────────────────┬──────────────────────────────────┘
                       │ LLMResponse + ValidationResult
                       ▼
┌─────────────────────────────────────────────────────────┐
│                Layer 4: 安全网关层                       │
│  InputSanitizer    → 输入清洗 + 注入防御                 │
│  ActionValidator   → 动作类型白名单校验                  │
│  ItemSafetyGuard   → 物品安全检查                       │
│  TeleportGuard     → WorldGuard 领地检查                 │
│  PotionGuard       → 药水效果白名单                      │
│  LLMResponseParser → JSON 容错解析 + 纯文本兜底         │
└──────────────────────┬──────────────────────────────────┘
                       │ 安全的执行指令
                       ▼
┌─────────────────────────────────────────────────────────┐
│                Layer 5: 执行层                           │
│                   ActionExecutor                         │
│  纯 Bukkit API 实现：                                    │
│  - 台词渲染（聊天框 + ActionBar + 音效）                 │
│  - 物品给予（纯净 ItemStack，零 NBT）                    │
│  - 传送（含 WorldGuard 领地检查）                        │
│  - 药水效果（含白名单 + 时长/等级截断）                  │
└─────────────────────────────────────────────────────────┘
```

### 横切关注点

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  记忆系统     │  │  审计系统     │  │  情绪系统     │
│  MemoryManager│  │  AuditLogger │  │  EmotionManager│
│  ┌──────────┐│  │  文件轮转     │  │  5档离散枚举  │
│  │ L1 Cache ││  │  MySQL双写    │  │  自动升降档   │
│  │ L2 SQLite││  └──────────────┘  └──────────────┘
│  └──────────┘│  ┌──────────────┐  ┌──────────────┐
│  压缩摘要     │  │  Token统计    │  │  画像系统     │
│  Compression │  │  TokenTracker │  │  ProfileMgr  │
└──────────────┘  └──────────────┘  └──────────────┘
```

---

## 核心机制

### 1. 异步处理流程

所有 LLM 调用在 Bukkit 异步线程执行，仅最后一步（物品给予/传送/发消息）同步回主线程：

```
AsyncPlayerChatEvent (异步线程)
  → ChatCollector.onPlayerChat()
    → InputSanitizer.sanitize()
    → AsyncDispatcher.submit()
      → [异步] RateLimiter.tryAcquire()
      → [异步] PromptBuilder.build()
      → [异步] CircuitBreaker.execute(LLMClient.sendAsync())
      → [异步] LLMResponseParser.parse()
      → [异步] ActionValidator.validate()
      → [异步] ItemSafetyGuard.check()
      → [异步] MemoryManager.appendAndPersist()
      → [异步] AuditLogger.logDialogueSuccess()
      → [异步] TokenTracker.track()
      → [主线程] ActionExecutor.execute()
      → [主线程] ChatCollector.markListeningAfterResponse()
```

### 2. 熔断器状态机

```
  CLOSED ──(连续失败≥阈值)──→ OPEN ──(超时恢复时间)──→ HALF_OPEN
    ↑                                                      │
    └──────────(成功)───────────────────────────────────────┘
                                                           │
                                              (失败)──→ OPEN
```

- `CLOSED`：正常状态，请求通过
- `OPEN`：熔断状态，请求快速失败，返回降级话术
- `HALF_OPEN`：尝试恢复，放行一次请求

### 3. 记忆双层存储

```
L1: Guava Cache（内存热缓存）
    - TTL 驱逐（默认 5 分钟无访问）
    - 最大 500 个会话
    - 驱逐时自动持久化到 L2

L2: SQLite（持久化）
    - chat_history 表
    - conversation_summary 表（压缩摘要）
    - player_profile 表（永久画像）
    - npc_emotion 表（情绪值）
```

### 4. 对话压缩流程

```
对话轮数 ≥ 阈值
  → 取出全部历史
  → 调用 LLM 生成第三人称摘要（独立 System Prompt）
  → 持久化摘要到 conversation_summary 表
  → 清空原始历史
  → 下次对话时，摘要注入 System Prompt
```

### 5. 注入防御三层纵深

```
Layer 1: InputSanitizer 正则过滤（已知注入模式）
Layer 2: InputSanitizer 标签逃逸剥离（</user_input> → < /user_input>）
Layer 3: PromptBuilder XML 隔离（用户输入包裹在 <user_input> 标签内）
```

---

## 项目结构

```
com.agenticnpc/
├── AgenticNPCPlugin.java          # 主类，组件装配
├── config/
│   ├── ConfigManager.java          # 配置读取
│   └── BrainConfig.java            # Brain 配置快照（record）
├── model/
│   ├── InteractionEvent.java       # 交互事件模型
│   ├── ChatMessage.java            # 对话消息
│   ├── LLMResponse.java            # LLM 结构化响应
│   ├── ActionParameters.java       # 动作参数
│   ├── ActionType.java             # 动作类型枚举
│   └── PromptPackage.java          # Prompt 包
├── hook/
│   ├── ChatCollector.java          # 对话状态机
│   ├── VanillaHook.java            # 原版实体 Hook
│   ├── CitizensHook.java           # Citizens Hook
│   ├── MythicMobsHook.java         # MythicMobs Hook
│   └── HookRegistry.java           # Hook 注册中心
├── dispatch/
│   ├── AsyncDispatcher.java        # 异步调度器（核心管线）
│   ├── LLMClient.java             # LLM HTTP 客户端
│   ├── CircuitBreaker.java         # 熔断器
│   ├── RateLimiter.java            # 令牌桶限流器
│   └── LLMException.java           # LLM 异常
├── gateway/
│   ├── InputSanitizer.java         # 输入清洗器
│   ├── ActionValidator.java        # 动作校验器
│   ├── ItemSafetyGuard.java        # 物品安全守卫
│   ├── LLMResponseParser.java      # LLM 响应解析器
│   └── model/                      # 安全检查结果模型
├── guard/
│   ├── TeleportGuard.java          # WorldGuard 传送守卫
│   └── PotionGuard.java            # 药水效果守卫
├── executor/
│   └── ActionExecutor.java         # 动作执行器
├── context/
│   └── PromptBuilder.java          # Prompt 构建器
├── memory/
│   ├── MemoryRepository.java       # 持久化接口
│   ├── SQLiteRepository.java       # SQLite 实现
│   ├── MemoryManager.java          # 记忆管理器
│   ├── CompressionService.java     # 对话压缩服务
│   └── PlayerProfileManager.java   # 永久画像管理器
├── emotion/
│   ├── EmotionLevel.java           # 情绪档位枚举
│   ├── EmotionChange.java          # 情绪变化枚举
│   └── EmotionManager.java         # 情绪管理器
├── audit/
│   ├── AuditLogger.java            # 审计日志系统
│   ├── AuditRepository.java        # 审计持久化接口
│   ├── MySQLAuditRepository.java   # MySQL 审计双写
│   └── TokenTracker.java           # Token 统计
├── storage/
│   ├── EntityBrainStorage.java     # 实体绑定接口
│   ├── PersistentDataStorage.java  # 1.14+ 实现
│   ├── MetadataStorage.java        # 1.12-1.13 兼容
│   └── EntityBrainStorageFactory.java
├── pipeline/
│   └── InteractionPipeline.java    # 管线接口
└── command/
    ├── BindCommand.java            # 绑定/管理命令
    ├── BindPendingListener.java    # 绑定操作监听
    └── StatsCommand.java           # Token 统计命令
```

---

## 扩展开发指南

### 添加新的 ActionType

1. 在 `ActionType.java` 枚举中新增值：

```java
public enum ActionType {
    NONE,
    GIVE_ITEM,
    TELEPORT,
    GIVE_EFFECT,
    SEND_TITLE;  // 新增
}
```

2. 在 `ActionParameters.java` 中新增参数字段：

```java
public record ActionParameters(
    // ... 现有字段 ...
    String title_text,     // 新增
    String title_subtitle  // 新增
) {}
```

3. 在 `ActionValidator.java` 中新增参数校验：

```java
if (actionType == ActionType.SEND_TITLE && response.action_parameters() == null) {
    return ValidationResult.invalid("SEND_TITLE 缺少 action_parameters");
}
```

4. 在 `ActionExecutor.java` 中新增执行逻辑：

```java
case SEND_TITLE:
    executeSendTitle(player, validation.parameters(), brain);
    break;
```

5. 在 `PromptBuilder.java` 的动态格式中新增说明：

```java
if (hasSendTitle) {
    sb.append("- SEND_TITLE: 向玩家发送标题文字\n");
}
```

6. 在 `BrainConfig` 的 `allowed-action-types` 中启用。

### 添加新的 Hook

1. 创建 Hook 类，实现 `Listener`：

```java
public class MyPluginHook implements Listener {
    private final EntityBrainStorage brainStorage;
    private final ChatCollector      chatCollector;

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(MyPluginEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getEntity();

        brainStorage.retrieve(entity).ifPresent(brainId -> {
            chatCollector.startListening(player, entity, brainId);
        });
    }
}
```

2. 在 `HookRegistry.registerAll()` 中注册：

```java
if (isClassPresent("com.myplugin.api.event.MyPluginEvent")) {
    register(new MyPluginHook(brainStorage, chatCollector, logger));
    logger.info("[Hook] MyPlugin Hook 已注册");
}
```

3. 在 `pom.xml` 中添加 `provided` 依赖。

### 第三方插件读写画像

```java
// 获取 AgenticNPC 实例
AgenticNPCPlugin plugin = (AgenticNPCPlugin) Bukkit.getPluginManager()
    .getPlugin("AgenticNPC");

// 获取画像管理器
PlayerProfileManager profileManager = plugin.getProfileManager();

// 读取画像
Optional<String> profile = profileManager.getProfile(playerUUID, "default_npc");

// 写入画像
profileManager.setProfile(playerUUID, "default_npc", "这是一个 VIP 玩家");

// 清除画像
profileManager.clearProfile(playerUUID, "default_npc");
```

---

## 数据库 Schema

### chat_history（对话历史）

| 列 | 类型 | 说明 |
|----|------|------|
| id | INTEGER PK | 自增 ID |
| player_uuid | TEXT | 玩家 UUID |
| brain_id | TEXT | Brain ID |
| role | TEXT | "user" / "assistant" |
| content | TEXT | 消息内容 |
| created_at | INTEGER | 时间戳(ms) |

### conversation_summary（对话摘要）

| 列 | 类型 | 说明 |
|----|------|------|
| player_uuid | TEXT PK | 玩家 UUID |
| brain_id | TEXT PK | Brain ID |
| summary | TEXT | 摘要内容 |
| token_count | INTEGER | Token 估算数 |
| round_count | INTEGER | 压缩时的对话轮数 |
| updated_at | INTEGER | 更新时间戳 |

### player_profile（永久画像）

| 列 | 类型 | 说明 |
|----|------|------|
| player_uuid | TEXT PK | 玩家 UUID |
| scope_id | TEXT PK | brain_id 或 group_id |
| scope_type | TEXT PK | "brain" 或 "entity" |
| profile | TEXT | 画像内容 |
| created_at | INTEGER | 创建时间戳 |
| updated_at | INTEGER | 更新时间戳 |

### npc_emotion（情绪值）

| 列 | 类型 | 说明 |
|----|------|------|
| player_uuid | TEXT PK | 玩家 UUID（或 "GLOBAL"） |
| brain_id | TEXT PK | Brain ID |
| emotion_level | TEXT | 情绪档位名 |
| updated_at | INTEGER | 更新时间戳 |

### audit_log（审计日志）

| 列 | 类型 | 说明 |
|----|------|------|
| id | INTEGER PK | 自增 ID |
| timestamp_ms | INTEGER | 时间戳 |
| player_uuid | TEXT | 玩家 UUID |
| player_name | TEXT | 玩家名 |
| brain_id | TEXT | Brain ID |
| event_type | TEXT | 事件类型 |
| input | TEXT | 玩家输入（截断） |
| output | TEXT | NPC 输出（截断） |
| action_type | TEXT | 动作类型 |
| server_id | TEXT | 服务器标识 |

### token_usage（Token 统计）

| 列 | 类型 | 说明 |
|----|------|------|
| id | INTEGER PK | 自增 ID |
| timestamp_ms | INTEGER | 时间戳 |
| player_uuid | TEXT | 玩家 UUID |
| brain_id | TEXT | Brain ID |
| prompt_tokens | INTEGER | Prompt Token 数 |
| completion_tokens | INTEGER | Completion Token 数 |
| total_tokens | INTEGER | 总 Token 数 |
| server_id | TEXT | 服务器标识 |

---

## v1.2 路线图

### 功能层面
- [ ] Citizens / MythicMobs 深度集成（NPC 名称显示、皮肤同步）
- [ ] MySQL 后端完整实现
- [ ] 更多 ActionType（SEND_TITLE、PLAY_SOUND、GIVE_XP）
- [ ] `/anpc emotion` 管理员命令
- [ ] `/anpc profile` Tab 补全
- [ ] 多语言支持（i18n）

### 安全层面
- [ ] 语义级 Prompt 注入防御（基于 LLM 自检）
- [ ] 审计日志完整规范（轮转 / 脱敏 / 异步写入）
- [ ] 审计日志 Web 查看面板

### 工程层面
- [ ] Redis 跨服限流（严格零误差）
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

## 构建

```bash
# 编译
mvn clean package

# 产物
target/agentic-npc-1.0-SNAPSHOT.jar

# 依赖
# - Spigot API 1.21.1 (provided)
# - Gson 2.10.1 (shaded)
# - Guava 33.2.1 (shaded)
# - HikariCP 5.1.0 (shaded)
# - SQLite JDBC 3.46.1.0 (shaded)
# - Citizens API 2.0.33 (provided, optional)
# - MythicMobs API 5.7.0 (provided, optional)
# - WorldGuard 7.1.0 (provided, optional)
```

所有 compile 依赖通过 maven-shade-plugin 重定位到 `com.agenticnpc.libs.*`，避免与服务器自带库冲突。
