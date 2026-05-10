# AgenticNPC 开发者指南

> 版本：v1.3-alpha | 最后更新：2026-05-10

---

## 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                    Layer 1: Hook 层                           │
│  VanillaHook │ CitizensHook │ MythicMobsHook                 │
│  监听玩家右键实体事件，标准化为 InteractionEvent               │
└───────────────────────┬──────────────────────────────────────┘
                        │ InteractionEvent
                        ▼
┌──────────────────────────────────────────────────────────────┐
│                Layer 2: 状态机层                              │
│                    ChatCollector                              │
│  管理玩家对话状态：LISTENING ↔ PROCESSING                    │
│  拦截聊天事件，输入清洗，提交管线                              │
└───────────────────────┬──────────────────────────────────────┘
                        │ InteractionEvent (已清洗)
                        ▼
┌──────────────────────────────────────────────────────────────┐
│               Layer 3: 异步调度层                             │
│                  AsyncDispatcher                              │
│  限流 → 语义审核 → Prompt → LLM → 解析 → 校验               │
│  → 安全检查 → 记忆追加 → 审计 → Token统计                    │
└───────────────────────┬──────────────────────────────────────┘
                        │ LLMResponse + ValidationResult
                        ▼
┌──────────────────────────────────────────────────────────────┐
│                Layer 4: 安全网关层                            │
│  InputSanitizer    → 输入清洗 + 注入防御                      │
│  SemanticGuard     → LLM 语义审核（SAFE/SUSPICIOUS/BLOCKED） │
│  ActionValidator   → 动作类型白名单 + 参数校验               │
│  ItemSafetyGuard   → 物品安全检查                            │
│  TeleportGuard     → WorldGuard 领地检查                     │
│  PotionGuard       → 药水效果白名单                          │
│  LLMResponseParser → JSON 容错解析 + 纯文本兜底              │
└───────────────────────┬──────────────────────────────────────┘
                        │ 安全的执行指令
                        ▼
┌──────────────────────────────────────────────────────────────┐
│                Layer 5: 执行层                                │
│                   ActionExecutor                              │
│  纯 Bukkit API：台词 + 物品 + 传送 + 药水                    │
│  + 标题 + 音效 + 经验值，全部主线程执行                       │
└──────────────────────────────────────────────────────────────┘
```

### 横切关注点

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  记忆系统     │  │  审计系统     │  │  情绪系统     │
│  MemoryManager│  │  AuditLogger │  │  EmotionManager│
│  ┌──────────┐│  │  JSONL 输出   │  │  5档离散枚举  │
│  │ L1 Cache ││  │  MySQL双写    │  │  自动升降档   │
│  │ L2 SQLite││  └──────────────┘  └──────────────┘
│  │   or     ││  ┌──────────────┐  ┌──────────────┐
│  │ L2 MySQL ││  │  Token统计    │  │  画像系统     │
│  └──────────┘│  │  TokenTracker │  │  ProfileMgr  │
│  压缩摘要     │  └──────────────┘  └──────────────┘
└──────────────┘
```

---

## 核心机制

### 1. 异步处理流程

```
AsyncPlayerChatEvent (异步线程)
  → ChatCollector.onPlayerChat()
    → InputSanitizer.sanitize()
    → AsyncDispatcher.submit()
      → [异步] RateLimiter.tryAcquire()
      → [异步] SemanticGuard.check()        ← S5-P1 新增
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

### 2. Fail-open 与 Fail-fast 原则

| 组件 | 策略 | 理由 |
|------|------|------|
| SemanticGuard | **fail-open** | 审核失败不应阻止正常对话 |
| RedisRateLimiter | **fail-open** | Redis 挂了不应阻止玩家交互 |
| MySQL 连接 | **fail-fast** | 数据一致性要求，静默降级会丢失数据 |
| LLM 熔断器 | **fail-fallback** | 返回降级话术 |

**实现原则：**
- fail-open：`catch (Exception) { return allow/SAFE; }` + WARN 日志
- fail-fast：构造函数抛异常，主类 `disablePlugin()`

### 3. Structured Audit Event

审计日志输出为 JSONL（每行一个 JSON 对象）：

```json
{
  "timestamp": 1746871234567,
  "time": "19:20:34.567",
  "event_type": "DIALOGUE_SUCCESS",
  "player_uuid": "uuid-string",
  "player_name": "Steve",
  "brain_id": "default_npc",
  "input": "你好",
  "output": "旅行者，你好。",
  "action_type": "NONE",
  "action_params": null,
  "server_id": "survival-1"
}
```

事件类型枚举：`DIALOGUE_SUCCESS`, `ACTION_BLOCKED`, `INJECTION_ATTEMPT`, `RATE_LIMITED`, `CIRCUIT_OPEN`, `PARSE_FAILED`, `SEMANTIC_GUARD_SUSPICIOUS`, `SEMANTIC_GUARD_BLOCKED`

### 4. 熔断器状态机

```
  CLOSED ──(连续失败≥阈值)──→ OPEN ──(超时恢复时间)──→ HALF_OPEN
    ↑                                                      │
    └──────────(成功)───────────────────────────────────────┘
                                                           │
                                              (失败)──→ OPEN
```

---

## ActionType 扩展规范

新增 ActionType 必须完成以下 **全部** 步骤：

### 1. 枚举注册
`ActionType.java` 新增枚举值。

### 2. 参数字段
`ActionParameters.java` 新增 record 字段（所有字段可空）。

### 3. 动作校验
`ActionValidator.java` 新增参数校验逻辑：
- 必填字段检查
- 范围/白名单校验
- 返回 `ValidationResult.invalid(reason)` 拒绝

### 4. 响应解析
`LLMResponseParser.java` — 通常 Gson 自动映射，无需改动。

### 5. 执行逻辑
`ActionExecutor.java` 新增 `executeXxx()` 方法：
- **必须在主线程执行**（Bukkit API 要求）
- 长度截断/范围裁剪在执行前完成
- 不允许 `Bukkit.dispatchCommand()`

### 6. 审计日志
`ActionExecutor.java` 中调用 `auditLogger.logDialogueSuccess()`:
- input 描述动作内容
- actionParams 记录关键参数
- 截断事件必须标记原始值和实际值

### 7. Prompt 注入
`PromptBuilder.java` 新增条件格式示例：
- 仅当 Brain 白名单包含该动作时注入
- 注明参数约束（长度、范围、白名单）

### 8. 配置扩展
`BrainConfig.java` + `ConfigManager.java` + `config.yml` 按需扩展。

### 9. 测试
- 单元测试：ActionValidator 校验逻辑（valid/invalid 两种路径）
- 回归测试：`prompt_regression.json` 新增 fixture
- 解析测试：LLMResponseParserTest 新增用例

---

## 测试规范

### 必须测试的场景

| 场景 | 测试类 |
|------|--------|
| 新动作校验逻辑 | `ActionValidatorTest` |
| LLM 响应解析 | `LLMResponseParserTest` |
| Prompt 回归 | `PromptRegressionTest` |
| 输入安全（注入防御） | `InputSanitizerTest` |
| fail-open 行为 | 对应组件的测试类 |

### Prompt Regression 测试

新增功能必须在 `src/test/resources/fixtures/prompt_regression.json` 中添加回归用例：

```json
{
  "description": "新动作的正常路径",
  "llm_output": "{\"dialogue\":\"...\",\"action_type\":\"NEW_ACTION\",\"action_parameters\":{...}}",
  "expected_action": "NEW_ACTION",
  "expected_dialogue_contains": "关键词"
}
```

### 运行测试

```bash
mvn clean test           # 运行所有测试
mvn clean package        # 编译 + 测试 + 打包
```

---

## Redis 限流架构

### 双模式设计

```
RateLimiter (接口)
  ├── LocalRateLimiter    ← Guava Cache + 令牌桶
  └── RedisRateLimiter    ← Jedis + Lua 原子窗口
```

配置切换：`rate-limit.backend: local | redis`

### Redis Lua 脚本（固定窗口）

```lua
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end
if current > tonumber(ARGV[1]) then
    return 0
end
return 1
```

### 已知限制

- 固定窗口边界可产生突刺（窗口切换时瞬间双倍请求）
- 改进方向：Sorted Set 滑动窗口

---

## 项目结构

```
com.agenticnpc/
├── AgenticNPCPlugin.java
├── config/
│   ├── ConfigManager.java
│   └── BrainConfig.java
├── model/
│   ├── InteractionEvent.java
│   ├── ChatMessage.java
│   ├── LLMResponse.java
│   ├── ActionParameters.java
│   ├── ActionType.java
│   └── PromptPackage.java
├── hook/
│   ├── ChatCollector.java
│   ├── VanillaHook.java
│   ├── CitizensHook.java
│   ├── MythicMobsHook.java
│   └── HookRegistry.java
├── dispatch/
│   ├── AsyncDispatcher.java
│   ├── LLMClient.java
│   ├── CircuitBreaker.java
│   ├── RateLimiter.java          ← 接口
│   ├── LocalRateLimiter.java
│   ├── RedisRateLimiter.java
│   └── LLMException.java
├── gateway/
│   ├── InputSanitizer.java
│   ├── SemanticGuard.java
│   ├── ActionValidator.java
│   ├── ItemSafetyGuard.java
│   ├── LLMResponseParser.java
│   └── model/
├── guard/
│   ├── TeleportGuard.java
│   └── PotionGuard.java
├── executor/
│   └── ActionExecutor.java
├── context/
│   └── PromptBuilder.java
├── memory/
│   ├── MemoryRepository.java
│   ├── SQLiteRepository.java
│   ├── MySQLRepository.java
│   ├── MemoryManager.java
│   ├── CompressionService.java
│   └── PlayerProfileManager.java
├── emotion/
│   ├── EmotionLevel.java
│   ├── EmotionChange.java
│   └── EmotionManager.java
├── audit/
│   ├── AuditLogger.java
│   ├── AuditRepository.java
│   ├── MySQLAuditRepository.java
│   └── TokenTracker.java
├── storage/
│   ├── EntityBrainStorage.java
│   ├── PersistentDataStorage.java
│   ├── MetadataStorage.java
│   └── EntityBrainStorageFactory.java
└── command/
    ├── BindCommand.java
    ├── BindPendingListener.java
    ├── StatsCommand.java
    ├── EmotionCommand.java
    └── HealthCommand.java
```

---

## 数据库 Schema

| 表 | 主键 | 用途 |
|----|------|------|
| `chat_history` | id (auto) | 对话历史 |
| `conversation_summary` | (player_uuid, brain_id) | 压缩摘要 |
| `player_profile` | (player_uuid, scope_id, scope_type) | 永久画像 |
| `npc_emotion` | (player_uuid, brain_id) | 情绪值 |
| `audit_log` | id (auto) | 审计日志 |
| `token_usage` | id (auto) | Token 统计 |

---

## 构建

```bash
mvn clean package

# 产物
target/agentic-npc-1.0-SNAPSHOT.jar  (~23MB)

# Shade 依赖
Gson, Guava, HikariCP, SQLite, MySQL, Jedis → com.agenticnpc.libs.*

# Provided 依赖（运行时由服务器提供）
Spigot API, Citizens, MythicMobs, WorldGuard
```

---

## Milestone 路线图

### Milestone A：稳定性验证
- [ ] 真实服务器长时间运行测试
- [ ] 压力测试（并发对话）
- [ ] Redis 断连恢复验证
- [ ] MySQL 长期运行数据一致性

### Milestone B：多服化
- [ ] BungeeCord 会话同步
- [ ] Redis Sorted Set 滑动窗口（替换固定窗口）
- [ ] SemanticGuard JSON 输出 + 结构化解析
- [ ] Redis fail-open 熔断保护

### Milestone C：可观测性
- [ ] Web Console（审计日志查看面板）
- [ ] Token 用量统计 Web 面板
- [ ] Prometheus metrics 导出
