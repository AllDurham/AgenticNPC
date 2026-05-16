# AgenticNPC

AI-driven NPC Agent system for Minecraft — powered by LLM with full-pipeline observability.

AgenticNPC transforms static NPC dialogue trees into intelligent, context-aware agents. Each NPC uses a **5-stage decision pipeline** to understand player intent, reason through LLM, validate safety, and execute in-game actions — all with production-grade observability and fault tolerance.

## Architecture

```
Player Input
    │
    ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ GuardStage  │────▶│ PromptStage │────▶│  LLMStage   │────▶│ResponseStage│────▶│ExecutionStage│
│             │     │             │     │             │     │             │     │             │
│ Rate limit  │     │ Dynamic     │     │ Circuit     │     │ JSON parse  │     │ Memory      │
│ Semantic    │     │ prompt      │     │ breaker     │     │ Action      │     │ persist     │
│ injection   │     │ assembly    │     │ LLM call    │     │ validate    │     │ Audit log   │
│ defense     │     │ (9 context  │     │ Token       │     │ Item safety │     │ Metrics     │
│             │     │  sections)  │     │ tracking    │     │ check       │     │ Game action │
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
    │                    │                    │                    │                    │
    └────────────────────┴────────────────────┴────────────────────┴────────────────────┘
                              TraceId (12-char UUID) flows through all stages
```

## Key Features

### Agent Pipeline (v1.4)

- **5-Stage Pipeline** — Guard → Prompt → LLM → Response → Execution, each stage independently observable
- **StageException vs Short-Circuit** — real failures (timeout, crash) vs business rejects (rate limit, validation) handled separately
- **Per-stage latency tracking** — EnumMap-based latency recording for each pipeline stage

### Full-Chain Observability

- **TraceId** — 12-char short UUID generated per interaction, flows through audit / metrics / prompt snapshots / logs
- **9-Section Token Breakdown** — system / profile / summary / emotion / playerInfo / actions / format / history / input
- **Pipeline Snapshot Ring Buffer** — last 100 pipeline executions with per-stage latency
- **Web Console** — real-time dashboard + prompt decision viewer (Javalin + cookie auth)

### Prompt Variant Experimentation (v1.4-c)

- **A/B prompt testing** — `prompt.variant: current | slim-v1` in config
- **Per-variant metrics** — avg tokens, fallback rate, action success rate, avg latency
- **Fixed cost tracking** — `formatTokens + actionTokens` isolated from dynamic context
- **70% warning** — highlights when fixed prompt cost dominates total tokens

### 6-Layer Security

1. **Rate Limiting** — per-player, local or Redis-backed
2. **Semantic Injection Guard** — secondary LLM call detects prompt injection attempts
3. **Action Validator** — whitelist-based action + parameter validation
4. **Item Safety Guard** — GIVE_ITEM safety checks
5. **Circuit Breaker** — auto-disables LLM calls on consecutive failures
6. **Input Sanitizer** — strips injection patterns before prompt assembly

### NPC Intelligence

- **Long-term Memory** — per-player per-NPC conversation history with automatic compression
- **Emotion System** — 5-level emotion (HOSTILE → WARY → NEUTRAL → FRIENDLY → DEVOTED) that influences NPC tone
- **Player Profiles** — admin-managed persistent player notes, highest priority in prompt context
- **6 Action Types** — GIVE_ITEM, TELEPORT, GIVE_EFFECT, SEND_TITLE, PLAY_SOUND, GIVE_XP

### Fault Tolerance

- **Degraded components** — memory/audit failures don't block NPC responses
- **5 parser fallback strategies** — standard JSON, markdown strip, bracket extraction, plaintext wrap, empty response handling
- **Circuit breaker** — auto-recovers after configured timeout
- **Player state recovery** — LISTENING state restored on any pipeline failure (no stuck sessions)

## Quick Start

### Prerequisites

- Minecraft Server (Spigot/Paper 1.13+)
- Java 17+
- DeepSeek API key (or any OpenAI-compatible endpoint)

### Install

1. Download `agentic-npc-1.0-SNAPSHOT.jar` from releases
2. Place in `plugins/` folder
3. Start server to generate `plugins/AgenticNPC/config.yml`
4. Edit `config.yml` — set your LLM API key
5. Restart server

### Configure an NPC

```yaml
# In config.yml
brains:
  - id: "village_merchant"
    name: "老张"
    personality: >
      你是村庄里的商人老张，精明但不失厚道。
      你对熟客会给予折扣，对新客人保持礼貌距离。
    fallback-dialogue: "让我想想..."
    allowed-action-types:
      - "GIVE_ITEM"
      - "PLAY_SOUND"
    allowed-items:
      - "BREAD"
      - "IRON_INGOT"
      - "DIAMOND"
    dialogue-sound: "ENTITY_VILLAGER_TRADE"
```

### Bind to an Entity

```
/anpc bind <entity> <brain_id>
```

Point at any NPC entity and run the command. The NPC is now AI-driven.

## Commands

| Command | Description |
|---------|-------------|
| `/anpc bind <entity> <brain>` | Bind an entity to a brain config |
| `/anpc unbind <entity>` | Unbind an entity |
| `/anpc stress <count>` | Pipeline stress test (OP only, max 200) |
| `/anpc emotion <player> <brain> <level>` | Set player emotion level |
| `/anpc profile <player> <brain> <text>` | Set player profile notes |
| `/anpc reload` | Reload configuration |
| `/anpc status` | Show system status |
| `/anpc health` | Show health metrics |

## Web Console

```yaml
web-console:
  enabled: true
  host: "127.0.0.1"
  port: 8080
  auth-token: "your-secret-token"
```

- **Dashboard** — system status, token metrics, error metrics, pipeline metrics, variant metrics, recent interactions
- **Prompt Viewer** — last 100 prompt decisions with full system prompt, token breakdown, fallback type, variant info

## Tech Stack

- **Runtime**: Java 17, Spigot API 1.13+
- **LLM**: DeepSeek API (OpenAI-compatible)
- **Storage**: SQLite / MySQL (memory persistence), Redis (optional, cross-server rate limiting)
- **Web**: Javalin 5 + custom Gson JSON mapper
- **Testing**: JUnit 5 + Mockito (173 tests)

## Project Structure

```
src/main/java/com/agenticnpc/
├── AgenticNPCPlugin.java          # Plugin entry point
├── audit/                          # JSONL audit logger
├── command/                        # /anpc commands + stress test
├── config/                         # ConfigManager + BrainConfig
├── console/                        # Web console + metrics + stores
│   ├── WebConsole.java            #   Javalin server + auth
│   ├── MetricsCollector.java      #   LongAdder + ring buffer
│   ├── PromptSnapshotStore.java   #   Prompt decision ring buffer
│   └── RecentInteractionStore.java
├── context/                        # Prompt assembly
│   └── PromptBuilder.java         #   9-section prompt + variant switch
├── dispatch/                       # Pipeline orchestrator
│   ├── AsyncDispatcher.java       #   Async scheduling + recovery
│   └── pipeline/                  #   5-stage pipeline framework
│       ├── PipelineContext.java
│       ├── PipelineExecutor.java
│       └── stages/                #   Guard/Prompt/LLM/Response/Execution
├── emotion/                        # 5-level emotion system
├── executor/                       # Bukkit main-thread action executor
├── gateway/                        # Input/output validation
│   ├── LLMResponseParser.java     #   5 fallback strategies
│   ├── ActionValidator.java       #   Whitelist validation
│   ├── ItemSafetyGuard.java       #   Item safety checks
│   └── SemanticGuard.java         #   Injection detection
├── hook/                           # Bukkit event listeners
├── memory/                         # Memory + compression + profiles
└── model/                          # Data models (records)
```

## License

MIT
