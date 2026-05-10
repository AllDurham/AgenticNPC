# AgenticNPC 用户指南（服主版）

> 版本：v1.3-alpha | 最后更新：2026-05-10

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

---

## 更新日志

### v1.3-alpha（当前版本）
- Redis 跨服限流（双模式）
- SemanticGuard 语义注入防御
- /anpc health 运行状态观测
- 单元测试体系（93 tests）
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
