# AgenticNPC 用户指南（服主版）

> 版本：v1.4-alpha | 最后更新：2026-05-11

---

## 插件特色

### AI 驱动的 NPC 对话

- 接入 OpenAI 兼容 API（DeepSeek / OpenAI / 中转站）
- 每个 NPC 独立人格、记忆、情绪，互不干扰
- 对话历史自动压缩为摘要，长期运行不爆 Token
- 管理员可为每个玩家设定永久画像，NPC 据此调整态度

### 7 种动作类型

| 动作 | 说明 |
|------|------|
| `GIVE_ITEM` | 给予物品（白名单 + 跨版本 Material 映射） |
| `TELEPORT` | 传送（WorldGuard 领地检查 + Y 坐标安全） |
| `GIVE_EFFECT` | 药水效果（白名单 + 时长/等级截断） |
| `SEND_TITLE` | 游戏内标题（长度截断 + §颜色代码安全） |
| `PLAY_SOUND` | 音效播放（sound-whitelist + 跨版本别名映射） |
| `GIVE_XP` | 经验值（单次上限截断 + 审计标记） |

### 六层纵深安全防御

```
玩家输入
  │
  ▼
Layer 1 ─ InputSanitizer 输入清洗
  │  • 长度截断（100 字符）
  │  • 注入模式正则匹配（6 类攻击签名）
  │  • 标签逃逸闭合符剥离（</user_input> 破坏）
  │  • Unicode NFKC 规范化（防同形字攻击）
  │  • 控制字符过滤
  │
  ▼
Layer 2 ─ SemanticGuard 语义审核（可选）
  │  • 独立 LLM 二次审核，不暴露主 System Prompt
  │  • 识别 Prompt 注入、越权诱导、jailbreak、系统探测
  │  • 三种判定：SAFE / SUSPICIOUS（记录不拦截）/ BLOCKED（拒绝）
  │  • 超时/异常自动 fail-open（不阻塞正常对话）
  │
  ▼
Layer 3 ─ PromptBuilder XML 隔离
  │  • 用户输入包裹在 <user_input> 标签内
  │  • 与 System Prompt 物理隔离，防止指令泄漏
  │
  ▼
Layer 4 ─ ActionValidator 动作校验
  │  • action_type 白名单校验（每个 Brain 独立配置）
  │  • 参数完整性检查
  │  • sound-whitelist 音效白名单
  │  • GIVE_XP 数量有效性
  │  • 未知动作类型自动降级为 NONE
  │
  ▼
Layer 5 ─ 专项安全守卫
  │  • ItemSafetyGuard：Material 解析 + 白名单 + 数量截断 + 跨版本别名
  │  • TeleportGuard：WorldGuard 领地白名单/黑名单
  │  • PotionGuard：药水效果白名单 + 时长/等级安全截断
  │
  ▼
Layer 6 ─ ActionExecutor 执行前截断
     • SEND_TITLE 标题/副标题长度截断 + §孤立标记剥离
     • SEND_TITLE 时长参数 [0, 200] ticks 裁剪
     • GIVE_XP 超限截断（审计日志标记原始值与实际值）
     • PLAY_SOUND 音量 [0.0, 2.0] / 音调 [0.5, 2.0] 裁剪
     • 所有动作执行均写入结构化审计日志
```

### 多服部署支持

- **Redis 跨服限流**：多服共享限流状态，Lua 原子操作，Redis 不可用时自动降级
- **MySQL 后端**：对话/画像/情绪/审计全量持久化，utf8mb4 字符集
- **服务器标识**：`server-id` 区分子服，审计日志和 Token 统计按服归属

### 可观测性

- `/anpc health`：一键查看熔断状态、限流后端、Redis 状态、Token 消耗、错误计数
- **Web 运维控制台**：浏览器打开 `http://localhost:8080/console`，实时查看系统状态、Token 指标、错误指标、最近 20 条对话
- **Prompt Snapshot Viewer**：回放 AI 决策过程（最近 100 条 Prompt + LLM 原始输出）
- 结构化审计日志（JSONL 格式）：每行一个 JSON，Web 面板可直接解析
- Token 告警：单次请求 / 每日玩家消耗超阈值自动告警
- 审计日志自动清理：按日期轮转，可配置保留天数

### 测试与质量

- 133 个单元测试（JUnit 5 + Mockito）
- Prompt 回归测试（JSON fixture 驱动，防止人格漂移）
- GitHub Actions CI（build → test → package）

---

## 5 分钟快速上手

### 第一步：安装插件

1. 将 `agentic-npc-1.0-SNAPSHOT.jar` 放入服务器的 `plugins/` 目录
2. 启动服务器，插件会自动生成 `plugins/AgenticNPC/config.yml`
3. 关闭服务器

### 第二步：配置 API Key

打开 `plugins/AgenticNPC/config.yml`，找到：

```yaml
llm:
  endpoint: "https://api.deepseek.com/chat/completions"
  api-key: "sk-在这里填写你的API密钥"
  model: "deepseek-v4-flash"
```

将 `api-key` 替换为你的真实 API Key。

### 第三步：启动服务器并绑定 NPC

1. 启动服务器
2. 在游戏中执行 `/anpc bind default_npc`
3. 右键一只猪或村民
4. 看到「绑定成功」提示

### 第四步：测试对话

1. 右键刚才绑定的实体
2. 屏幕出现 Title「艾尔文」
3. 在聊天框输入「你好」
4. 等待 1-3 秒，NPC 回复

**恭喜，MVP 已跑通！**

---

## 安装要求

| 项目 | 最低要求 |
|------|----------|
| Minecraft 版本 | 1.13+（推荐 1.21） |
| 服务端 | Spigot / Paper |
| Java | 17+ |
| API | OpenAI 兼容 API（DeepSeek / OpenAI / 中转） |
| Redis（可选） | 6.0+（跨服限流时需要） |
| MySQL（可选） | 8.0+（使用 MySQL 后端时需要） |

---

## 命令大全

| 命令 | 说明 | 权限 |
|------|------|------|
| `/anpc bind <brainId>` | 将 Brain 绑定到右键的实体 | `agenticnpc.admin` |
| `/anpc unbind` | 解绑右键的实体 | `agenticnpc.admin` |
| `/anpc status` | 查看实体绑定状态 | `agenticnpc.admin` |
| `/anpc reload` | 热重载配置（无需重启） | `agenticnpc.admin` |
| `/anpc stats` | 查看今日全局 Token 统计 | `agenticnpc.admin` |
| `/anpc stats player <名字>` | 查看指定玩家 Token 消耗 | `agenticnpc.admin` |
| `/anpc stats brain <id>` | 查看指定 Brain Token 消耗 | `agenticnpc.admin` |
| `/anpc emotion <玩家> <brainId> <档位>` | 手动设置玩家情绪档位 | `agenticnpc.admin.emotion` |
| `/anpc profile set <玩家> <brainId> <内容>` | 设置玩家画像 | `agenticnpc.admin` |
| `/anpc profile get <玩家> [brainId]` | 查看玩家画像 | `agenticnpc.admin` |
| `/anpc profile delete <玩家> [brainId]` | 删除玩家画像 | `agenticnpc.admin` |
| `/anpc health` | 查看运行状态 | `agenticnpc.admin` |

---

## 配置文件详解

### LLM 配置

```yaml
llm:
  endpoint: "https://api.deepseek.com/chat/completions"
  api-key: "sk-xxx"
  model: "deepseek-v4-flash"
  temperature: 0.8          # 创造性（0.0-1.0）
  max-tokens: 1024          # 最大回复长度
  timeout:
    connect-ms: 3000
    read-ms: 8000
```

### 限流配置

```yaml
rate-limit:
  backend: "local"           # local=单服 / redis=跨服
  global-per-player: false   # true=玩家维度 / false=玩家+Brain 维度
  max-requests: 5            # 窗口内最大请求数
  period-ms: 60000           # 窗口时长（毫秒）
```

### Redis 配置（跨服限流）

```yaml
redis:
  host: "localhost"
  port: 6379
  password: ""               # 留空表示无密码
  database: 0
  timeout-ms: 2000           # 连接超时
```

Redis 不可用时插件自动降级为 fail-open（允许请求通过，输出 WARN 日志）。

### MySQL 配置

```yaml
database:
  backend: "sqlite"          # sqlite=本地 / mysql=远程
  sqlite:
    file: "memories.db"
  mysql:
    host: "localhost"
    port: 3306
    database: "agentic_npc"
    username: "root"
    password: "your_password"
```

MySQL 连接失败时插件以 fail-fast 方式禁用自身（不会静默降级）。

### NPC Brain 配置

```yaml
brains:
  - id: "default_npc"
    name: "艾尔文"
    personality: >
      你是一个神秘的旅行者...
    fallback-dialogue: "..."
    allowed-action-types:
      - "GIVE_ITEM"
      - "TELEPORT"
      - "GIVE_EFFECT"
      - "SEND_TITLE"
      - "PLAY_SOUND"
      - "GIVE_XP"
    allowed-items:
      - "BREAD"
      - "APPLE"
    allowed-potion-effects:
      - "HEAL"
      - "SPEED"
    sound-whitelist:                    # PLAY_SOUND 可用音效
      - "ENTITY_VILLAGER_AMBIENT"
      - "ENTITY_VILLAGER_HAPPY"
    dialogue-sound: "ENTITY_VILLAGER_AMBIENT"
    dialogue-sound-volume: 1.0
    dialogue-sound-pitch: 1.0
    action-bar-enabled: true
    profile-scope: "brain"
    emotion-shared: false
    default-emotion: "NEUTRAL"
```

### SemanticGuard 配置（语义注入防御）

```yaml
semantic-guard:
  enabled: false             # 是否启用
  model: "deepseek-v4-flash" # 审核模型（留空继承主模型）
  endpoint: ""               # 独立端点（留空继承主端点）
  api-key: ""                # 独立密钥（留空继承主密钥）
  timeout-ms: 2000           # 审核超时
  mode: "detect"             # detect=仅检测记录 / strict=拦截
```

审核超时或失败时自动 fail-open（允许请求继续）。

### Web 控制台配置

```yaml
web-console:
  enabled: false              # 是否启用
  host: "127.0.0.1"           # 监听地址（默认仅本机，不允许 0.0.0.0 作为默认值）
  port: 8080                  # 端口
  auth-token: "change-me"     # 认证 Token（必须修改！留空或使用默认值时拒绝启动）
```

访问方式：浏览器打开 `http://127.0.0.1:8080/console`，输入 auth-token 登录。

> **安全提醒**：必须修改 `auth-token`，插件会在启动时检查是否为默认值。token 不会出现在日志中。

### 其他配置

```yaml
# 压缩
compression:
  exit-threshold: 5          # 退出时超过此轮数触发压缩
  active-threshold: 10       # 对话中超过此轮数触发压缩

# 情绪
emotion:
  enabled: true

# XP 限制
xp:
  max-per-action: 1000       # 单次给予经验值上限

# 审计
audit:
  enabled: true
  auto-cleanup: true
  retention-days: 30

# 服务器标识（多服部署时区分子服）
server-id: "survival-1"
```

---

## 支持的动作类型

| 动作 | 说明 | 示例 |
|------|------|------|
| `NONE` | 只说话 | 大多数对话 |
| `GIVE_ITEM` | 给予物品 | "给我一些面包" |
| `TELEPORT` | 传送到坐标 | "送我去村庄" |
| `GIVE_EFFECT` | 给予药水效果 | "给我加个治疗" |
| `SEND_TITLE` | 发送游戏标题 | "给我看个标题" |
| `PLAY_SOUND` | 播放音效 | "播放一个音效" |
| `GIVE_XP` | 给予经验值 | "教我一些经验" |

---

## /anpc health 输出说明

```
========= AgenticNPC Health =========
LLM 熔断器:      CLOSED         ← CLOSED=正常, OPEN=熔断, HALF_OPEN=恢复中
限流后端:        redis          ← local=单服, redis=跨服
Redis 状态:      PONG           ← PONG=正常, DEGRADED=不可用
今日 Token:      1234 (请求 5 次)
Memory 后端:     MySQL          ← SQLite 或 MySQL
SemanticGuard:   启用           ← 启用/禁用
最近错误(60s):   0              ← 0=正常, <5=警告, ≥5=异常
服务器 ID:       survival-1
=====================================
```

---

## 生产环境推荐配置

```yaml
# ---- LLM ----
llm:
  temperature: 0.7           # 略低创造性，更稳定
  max-tokens: 512            # 控制 Token 消耗

# ---- 限流 ----
rate-limit:
  backend: "redis"           # 多服必须用 redis
  max-requests: 3            # 生产环境适当收紧
  period-ms: 60000

# ---- 数据库 ----
database:
  backend: "mysql"           # 生产环境推荐 MySQL

# ---- 安全 ----
semantic-guard:
  enabled: true              # 生产环境建议开启
  timeout-ms: 1500           # 略低于主 LLM 超时

# ---- 审计 ----
audit:
  enabled: true
  auto-cleanup: true
  retention-days: 30

# ---- 调试 ----
debug: false                 # 生产环境关闭调试日志
```

---

## 权限节点

| 权限 | 说明 | 默认 |
|------|------|------|
| `agenticnpc.admin` | 管理命令（bind/unbind/reload/stats/health/profile） | OP |
| `agenticnpc.admin.emotion` | 情绪管理命令（/anpc emotion） | OP |

---

## FAQ

### Q1: NPC 没有回复怎么办？

1. 检查 `api-key` 是否正确
2. 检查 `debug: true` 后查看控制台日志
3. 确认 API 端点可访问
4. 检查 `/anpc health` 中的熔断器状态

### Q2: Redis 配置后日志报连接失败？

插件会自动降级为 fail-open（允许请求通过）。检查：
1. Redis 服务是否启动
2. host/port/password 是否正确
3. 防火墙是否开放端口

### Q3: 如何切换到 MySQL 后端？

修改 `database.backend: mysql`，配置 MySQL 连接信息，重启服务器。注意：MySQL 连接失败时插件会禁用自身（fail-fast），不会静默降级。

### Q4: Token 消耗太大怎么办？

1. 降低 `max-tokens`（如 512）
2. 缩短 `personality`
3. 减少 `max-history-per-session`
4. 使用更便宜的模型
5. 启用 SemanticGuard 过滤无意义对话

### Q5: 插件会影响服务器性能吗？

影响极小。所有 LLM 调用在异步线程执行。`/anpc health` 的「最近错误(60s)」可以帮助监控异常。

### Q6: 如何启用 Web 控制台？

1. 修改 `config.yml` 中 `web-console.enabled: true`
2. 修改 `auth-token` 为自定义强密码
3. 重启服务器或执行 `/anpc reload`
4. 浏览器打开 `http://你的服务器IP:8080/console`（默认仅 localhost 可访问）

如果需要远程访问，将 `host` 改为服务器 IP，并配置防火墙放行端口。

### Q7: Web 控制台安全吗？

- 默认仅监听 `127.0.0.1`（本机访问）
- 必须 Bearer Token 认证（拒绝默认值 `change-me`）
- Token 不打印到日志
- Web 服务异常不影响主插件
- Prompt 快照仅保存在内存中，不落盘

---

## 更新日志

### v1.4-alpha（当前版本）
- Web 运维控制台（Javalin，暗色主题 Dashboard）
- 实时 Dashboard：系统状态 + Token 指标 + 错误指标 + 最近 20 条对话
- Prompt Snapshot Viewer：回放 AI 决策过程（最近 100 条）
- Metrics API（/api/dashboard, /api/metrics, /api/interactions, /api/prompts）
- Bearer Token 认证（localhost-only 默认，拒绝默认 token）
- 统一指标采集器（MetricsCollector，滑动窗口，零线程开销）
- 测试用例增至 133 个

### v1.3-alpha
- Redis 跨服限流（双模式）
- SemanticGuard 语义注入防御
- /anpc health 运行状态观测
- 单元测试体系（133 tests）
- CI Pipeline（GitHub Actions）
- Structured Audit（JSONL 格式）

### v1.2
- SEND_TITLE / PLAY_SOUND / GIVE_XP 新动作
- MySQL 完整后端
- /anpc emotion 管理员命令
- 审计日志自动清理
- sound-whitelist 音效白名单

### v1.1
- Citizens / MythicMobs 生态集成
- TELEPORT / GIVE_EFFECT 动作
- WorldGuard 领地守卫
- 审计日志 + Token 统计
- 对话摘要压缩 + 永久画像 + 情绪系统

### v1.0（MVP）
- 核心对话链路 + 物品给予
- 安全网关 + SQLite 记忆
- 熔断器 + 限流器
