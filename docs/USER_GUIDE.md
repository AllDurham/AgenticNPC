# AgenticNPC 用户指南（服主版）

> 版本：v1.1 | 最后更新：2026-05-10

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

---

## 配置文件详解

### LLM 配置

```yaml
llm:
  endpoint: "https://api.deepseek.com/chat/completions"  # API 地址
  api-key: "sk-xxx"                                        # API 密钥
  model: "deepseek-v4-flash"                               # 模型名
  temperature: 0.8          # 创造性（0.0-1.0，越高越随机）
  max-tokens: 1024          # 最大回复长度
  timeout:
    connect-ms: 3000        # 连接超时
    read-ms: 8000           # 读取超时
```

### NPC Brain 配置

每个 Brain 是一个独立的 NPC 角色：

```yaml
brains:
  - id: "default_npc"           # 唯一标识（绑定时使用）
    name: "艾尔文"               # 游戏内显示名
    personality: >               # 角色人设（越详细越好）
      你是一个神秘的旅行者...
    fallback-dialogue: "..."     # API 故障时的降级话术
    allowed-action-types:        # 允许的动作类型
      - "GIVE_ITEM"
      - "TELEPORT"
      - "GIVE_EFFECT"
    allowed-items:               # 允许给予的物品白名单
      - "BREAD"
      - "APPLE"
    allowed-potion-effects:      # 允许施加的药水效果白名单
      - "HEAL"
      - "SPEED"
    profile-scope: "brain"       # 画像共享范围
    emotion-shared: false        # 情绪是否全体共享
    default-emotion: "NEUTRAL"   # 初始情绪档位
```

### 压缩配置

```yaml
compression:
  exit-threshold: 5          # 玩家退出时，对话轮数超过此值触发压缩
  active-threshold: 10       # 对话中，轮数超过此值触发主动压缩
  max-summary-tokens: 1024   # 压缩摘要的最大 Token 数
  model: ""                  # 压缩专用模型（留空继承主模型）
```

### 情绪系统

```yaml
emotion:
  enabled: true              # 是否启用情绪系统
```

NPC 情绪分为 5 档：敌对 → 警惕 → 中立 → 友善 → 忠诚。情绪会根据对话内容自动变化，影响 NPC 的语气和行为倾向。

### 传送守卫（WorldGuard）

```yaml
teleport-guard:
  enabled: false             # 是否启用 WorldGuard 领地检查
  mode: "blacklist"          # blacklist=禁止传送到列表领地 / whitelist=只允许
  regions:
    - "spawn"
    - "pvp_arena"
```

### 审计日志

```yaml
audit:
  enabled: true              # 是否启用审计日志
  auto-cleanup: false        # 是否自动清理旧日志
  retention-days: 30         # 日志保留天数
```

审计日志输出到 `plugins/AgenticNPC/audit-logs/` 目录，按日期自动切割。

---

## 玩家画像系统

画像让 NPC 对玩家有「先验了解」，由管理员手动维护。

```
/anpc profile set <玩家名> <brainId> <画像内容>
/anpc profile get <玩家名> [brainId]
/anpc profile clear <玩家名> [brainId]
```

示例：
```
/anpc profile set Steve default_npc 这是一个慷慨的老玩家，经常帮助新人，喜欢探索
```

画像会注入到 NPC 的 System Prompt 中，NPC 会根据画像内容调整对玩家的态度。

---

## 支持的动作类型

| 动作 | 说明 | 示例 Prompt |
|------|------|-------------|
| `NONE` | 只说话，不执行动作 | 大多数对话 |
| `GIVE_ITEM` | 给予玩家物品 | "给我一些面包" |
| `TELEPORT` | 传送玩家到指定坐标 | "送我去村庄" |
| `GIVE_EFFECT` | 给予药水效果 | "给我加个治疗" |

---

## FAQ

### Q1: NPC 没有回复怎么办？

1. 检查 `config.yml` 中 `api-key` 是否正确
2. 检查 `debug: true` 后查看控制台日志
3. 确认 API 端点可访问（国内可能需要中转）
4. 检查熔断器是否打开（日志搜索「熔断器」）

### Q2: NPC 回复了但没有给物品？

1. 检查 `allowed-items` 是否包含该物品
2. 检查物品名是否为 Minecraft 1.12 规范名（全大写英文）
3. 查看日志中的「物品安全检查失败」信息

### Q3: 如何添加新的 NPC 角色？

在 `config.yml` 的 `brains` 列表中新增一个条目，设置不同的 `id`、`name`、`personality`，然后用 `/anpc bind <新id>` 绑定到实体。

### Q4: 对话历史会无限增长吗？

不会。每轮对话有滑动窗口限制（`max-history-per-session`，默认 10 轮）。超出后自动压缩为摘要，释放内存和 Token 空间。

### Q5: 玩家输入会被注入攻击吗？

我们有三层防御：
1. 正则过滤已知注入模式
2. XML 标签隔离用户输入
3. 闭合符剥离（`</user_input>` 被破坏）

### Q6: 支持 BungeeCord 多服吗？

当前版本采用「本地令牌桶 + MySQL 30 秒同步」策略，允许约 30 秒的跨服误差窗口。严格零误差限流将在 v1.2 的 Redis 支持中实现。

### Q7: 如何更换 API 提供商？

修改 `config.yml` 中的 `llm.endpoint`、`llm.api-key`、`llm.model`，然后执行 `/anpc reload`，无需重启服务器。

### Q8: Token 消耗太大怎么办？

1. 降低 `max-tokens`（如 512）
2. 缩短 NPC 的 `personality`（人设越短，System Prompt 越短）
3. 减少 `max-history-per-session`（历史越少，Token 越少）
4. 使用更便宜的模型
5. 启用压缩系统（`compression.active-threshold` 调低）

### Q9: 如何查看 NPC 的情绪状态？

目前通过日志查看（`debug: true` 时会输出情绪变化）。v1.2 将添加 `/anpc emotion` 命令。

### Q10: 插件会影响服务器性能吗？

影响极小。所有 LLM 调用在异步线程执行，不阻塞主线程。本地限流和缓存均为内存操作，开销可忽略。

---

## 权限节点

| 权限 | 说明 | 默认 |
|------|------|------|
| `agenticnpc.admin` | 管理命令（bind/unbind/reload/stats） | OP |

---

## 更新日志

### v1.1（当前版本）
- Citizens / MythicMobs 生态集成
- TELEPORT / GIVE_EFFECT 新动作
- WorldGuard 领地传送守卫
- 药水效果白名单
- 审计日志系统
- Token 统计系统
- 对话摘要压缩
- 永久玩家画像
- 情绪值系统
- 热重载完善

### v1.0（MVP）
- 核心对话链路
- 物品给予
- 安全网关（输入清洗 + 动作校验 + 物品守卫）
- SQLite 记忆系统
- 熔断器 + 限流器
