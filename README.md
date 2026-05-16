# AgenticNPC

**基于 LLM 的 Minecraft 智能 NPC Agent 系统** | **AI-Driven NPC Agent System for Minecraft**

---

## 中文

### 这是什么

AgenticNPC 让 Minecraft 服务器中的 NPC 具备 AI 对话与自主行为能力。NPC 不再是固定对话树，而是能理解玩家自然语言、根据上下文动态决策、并执行游戏内动作（给予物品、传送、施加效果等）的智能 Agent。

### 核心架构

每次玩家与 NPC 交互，经过一条 **5 阶段 Pipeline**：

```
玩家输入 → 安全门控 → Prompt 组装 → LLM 推理 → 响应校验 → 动作执行
         GuardStage  PromptStage  LLMStage  ResponseStage ExecutionStage
```

每个阶段独立可观测，带全链路 TraceId 追踪。

### 核心特性

**Pipeline 架构 (v1.4)**
- 5 阶段串行决策链，每阶段独立 latency 追踪
- 正常业务拒绝（限流/校验失败）与真异常（超时/崩溃）分开处理
- Pipeline Snapshot 环形缓冲区，记录最近 100 次执行的全阶段耗时

**全链路可观测**
- 每次交互生成 12 位 TraceId，贯穿审计日志 / 指标 / Prompt 快照 / 日志
- 9 段 Token 分解：system / profile / summary / emotion / playerInfo / actions / format / history / input
- Web 控制台：实时 Dashboard + Prompt 决策快照浏览器

**Prompt 变体实验系统 (v1.4-c)**
- 配置切换 `prompt.variant: current | slim-v1`，A/B 对比不同 prompt 策略
- Per-variant 指标：平均 Token、Fallback 率、Action 成功率、平均延迟
- 固定成本（format + actions）与动态上下文分离追踪，70% 超预算预警

**6 层安全防御**
1. 速率限制（本地 / Redis 双后端）
2. 语义注入检测（SemanticGuard 二次 LLM 调用）
3. 动作白名单校验（ActionValidator）
4. 物品安全检查（ItemSafetyGuard）
5. 熔断器自动降级（CircuitBreaker）
6. 输入净化（InputSanitizer）

**NPC 智能能力**
- 长期记忆：每玩家每 NPC 的对话历史，自动压缩摘要
- 情绪系统：5 级情绪变化（敌对 → 警惕 → 中立 → 友善 → 忠诚），影响 NPC 语气
- 玩家画像：管理员可写入永久备注，NPC 优先参考
- 6 种动作类型：GIVE_ITEM / TELEPORT / GIVE_EFFECT / SEND_TITLE / PLAY_SOUND / GIVE_XP

**容错设计**
- 记忆 / 审计为降级组件，故障不阻断 NPC 回复
- 5 种 LLM 响应解析兜底策略（标准 JSON / Markdown 剥离 / 括号提取 / 纯文本包装 / 空响应处理）
- Pipeline 任意阶段失败自动恢复玩家会话状态（不卡死）

### 快速开始

**前置条件：** Minecraft 服务器（Spigot/Paper 1.13+）、Java 17、LLM API Key

```bash
# 1. 将 JAR 放入 plugins/ 目录
# 2. 启动服务器生成配置文件
# 3. 编辑 plugins/AgenticNPC/config.yml，填入 API Key
# 4. 重启服务器
# 5. 游戏内绑定 NPC
/anpc bind <实体> <brain_id>
```

### NPC 配置示例

```yaml
brains:
  - id: "village_merchant"
    name: "老张"
    personality: >
      你是村庄里的商人老张，精明但不失厚道。
      你对熟客会给予折扣，对新客人保持礼貌距离。
    fallback-dialogue: "让我想想..."
    allowed-action-types: ["GIVE_ITEM", "PLAY_SOUND"]
    allowed-items: ["BREAD", "IRON_INGOT", "DIAMOND"]
```

### 命令

| 命令 | 说明 |
|------|------|
| `/anpc bind <实体> <brain>` | 绑定实体到 brain 配置 |
| `/anpc unbind <实体>` | 解绑实体 |
| `/anpc stress <次数>` | Pipeline 压力测试（OP，上限 200） |
| `/anpc emotion <玩家> <brain> <等级>` | 设置玩家情绪等级 |
| `/anpc profile <玩家> <brain> <文本>` | 设置玩家画像备注 |
| `/anpc reload` | 重载配置 |
| `/anpc status` | 系统状态 |
| `/anpc health` | 健康指标 |

### Web 控制台

```yaml
web-console:
  enabled: true
  host: "127.0.0.1"
  port: 8080
  auth-token: "your-secret-token"
```

- **Dashboard** — 系统状态、Token 指标、错误指标、Pipeline 指标、Variant 指标、最近交互
- **Prompt Viewer** — 最近 100 条 Prompt 决策，含完整 system prompt、Token 分解、Fallback 类型、Variant 信息

### 技术栈

| 层 | 技术 |
|----|------|
| 运行时 | Java 17, Spigot API 1.13+ |
| LLM | DeepSeek API（兼容 OpenAI 接口） |
| 存储 | SQLite / MySQL（记忆持久化）、Redis（可选，跨服限流） |
| Web | Javalin 5 + 自定义 Gson JSON Mapper |
| 测试 | JUnit 5 + Mockito，173 个测试用例 |

---

## English

### What is AgenticNPC

AgenticNPC is an AI-driven NPC Agent system for Minecraft servers. Instead of static dialogue trees, NPCs powered by AgenticNPC understand natural language, make context-aware decisions, and execute in-game actions — acting as intelligent agents with memory, emotion, and personality.

### Architecture

Every player-NPC interaction flows through a **5-stage Pipeline**:

```
Player Input → Guard → Prompt → LLM → Response → Execution
              (rate    (9-section   (circuit  (JSON parse   (memory +
               limit,   dynamic     breaker,  action check, audit,
               inject   prompt      token     item safety)  game
               detect)  assembly)   tracking)               action)
```

Each stage is independently observable with full-chain TraceId tracing.

### Key Features

**Pipeline Architecture (v1.4)**
- 5-stage serial decision chain with per-stage latency tracking
- Business rejects (rate limit / validation) vs real failures (timeout / crash) handled separately
- Pipeline Snapshot ring buffer recording last 100 executions with per-stage timing

**Full-Chain Observability**
- 12-char TraceId per interaction, flowing through audit / metrics / prompt snapshots / logs
- 9-section Token Breakdown: system / profile / summary / emotion / playerInfo / actions / format / history / input
- Web Console: real-time Dashboard + Prompt decision snapshot viewer

**Prompt Variant Experimentation (v1.4-c)**
- Config-switchable `prompt.variant: current | slim-v1` for A/B prompt testing
- Per-variant metrics: avg tokens, fallback rate, action success rate, avg latency
- Fixed cost (format + actions) isolated from dynamic context, with 70% over-budget warning

**6-Layer Security**
1. Rate limiting (local / Redis dual backend)
2. Semantic injection detection (secondary LLM call via SemanticGuard)
3. Action whitelist validation (ActionValidator)
4. Item safety checking (ItemSafetyGuard)
5. Circuit breaker auto-degradation
6. Input sanitization (InputSanitizer)

**NPC Intelligence**
- Long-term memory: per-player per-NPC conversation history with automatic compression
- Emotion system: 5-level emotion (HOSTILE → WARY → NEUTRAL → FRIENDLY → DEVOTED) affecting NPC tone
- Player profiles: admin-managed persistent notes, highest priority in prompt context
- 6 action types: GIVE_ITEM / TELEPORT / GIVE_EFFECT / SEND_TITLE / PLAY_SOUND / GIVE_XP

**Fault Tolerance**
- Memory / audit as degraded components — failures don't block NPC responses
- 5 LLM response parsing fallback strategies
- Automatic player session state recovery on any pipeline failure

### Quick Start

**Requirements:** Minecraft server (Spigot/Paper 1.13+), Java 17, LLM API key

```bash
# 1. Place JAR in plugins/ directory
# 2. Start server to generate config
# 3. Edit plugins/AgenticNPC/config.yml with your API key
# 4. Restart server
# 5. Bind NPC in-game
/anpc bind <entity> <brain_id>
```

### Brain Config Example

```yaml
brains:
  - id: "village_merchant"
    name: "Old Zhang"
    personality: >
      You are Old Zhang, a shrewd but fair village merchant.
      You offer discounts to regulars and stay polite with newcomers.
    fallback-dialogue: "Let me think..."
    allowed-action-types: ["GIVE_ITEM", "PLAY_SOUND"]
    allowed-items: ["BREAD", "IRON_INGOT", "DIAMOND"]
```

### Commands

| Command | Description |
|---------|-------------|
| `/anpc bind <entity> <brain>` | Bind entity to brain config |
| `/anpc unbind <entity>` | Unbind entity |
| `/anpc stress <count>` | Pipeline stress test (OP only, max 200) |
| `/anpc emotion <player> <brain> <level>` | Set player emotion level |
| `/anpc profile <player> <brain> <text>` | Set player profile notes |
| `/anpc reload` | Reload configuration |
| `/anpc status` | System status |
| `/anpc health` | Health metrics |

### Web Console

- **Dashboard** — system status, token metrics, error metrics, pipeline metrics, variant metrics, recent interactions
- **Prompt Viewer** — last 100 prompt decisions with full system prompt, token breakdown, fallback type, variant info

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 17, Spigot API 1.13+ |
| LLM | DeepSeek API (OpenAI-compatible) |
| Storage | SQLite / MySQL (memory), Redis (optional, cross-server rate limiting) |
| Web | Javalin 5 + custom Gson JSON Mapper |
| Testing | JUnit 5 + Mockito, 173 test cases |

### License

MIT
