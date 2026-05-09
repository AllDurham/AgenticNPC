package com.agenticnpc;

import com.agenticnpc.audit.AuditLogger;
import com.agenticnpc.audit.TokenTracker;
import com.agenticnpc.command.BindCommand;
import com.agenticnpc.command.BindPendingListener;
import com.agenticnpc.command.StatsCommand;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.context.PromptBuilder;
import com.agenticnpc.dispatch.AsyncDispatcher;
import com.agenticnpc.dispatch.CircuitBreaker;
import com.agenticnpc.dispatch.LLMClient;
import com.agenticnpc.dispatch.RateLimiter;
import com.agenticnpc.executor.ActionExecutor;
import com.agenticnpc.gateway.ActionValidator;
import com.agenticnpc.guard.PotionGuard;
import com.agenticnpc.guard.TeleportGuard;
import com.agenticnpc.gateway.InputSanitizer;
import com.agenticnpc.gateway.ItemSafetyGuard;
import com.agenticnpc.gateway.LLMResponseParser;
import com.agenticnpc.hook.ChatCollector;
import com.agenticnpc.hook.HookRegistry;
import com.agenticnpc.memory.MemoryManager;
import com.agenticnpc.memory.MemoryRepository;
import com.agenticnpc.memory.SQLiteRepository;
import com.agenticnpc.storage.EntityBrainStorage;
import com.agenticnpc.storage.EntityBrainStorageFactory;
import org.bukkit.plugin.java.JavaPlugin;

public class AgenticNPCPlugin extends JavaPlugin {

    private static AgenticNPCPlugin instance;

    // ---- 配置 ----
    private ConfigManager configManager;

    // ---- 安全组件 ----
    private InputSanitizer    inputSanitizer;
    private ItemSafetyGuard   itemSafetyGuard;
    private ActionValidator   actionValidator;
    private LLMResponseParser responseParser;

    // ---- 记忆系统 ----
    private MemoryRepository memoryRepository;
    private MemoryManager    memoryManager;

    // ---- 存储 + 状态机 ----
    private EntityBrainStorage brainStorage;
    private ChatCollector      chatCollector;

    // ---- 通信层 ----
    private LLMClient       llmClient;
    private CircuitBreaker  circuitBreaker;
    private RateLimiter     rateLimiter;
    private PromptBuilder   promptBuilder;
    private AsyncDispatcher asyncDispatcher;

    // ---- 执行层 ----
    private ActionExecutor actionExecutor;

    // ---- Sprint 2: 审计 + Token ----
    private AuditLogger  auditLogger;
    private TokenTracker tokenTracker;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        getLogger().info("========================================");
        getLogger().info("  AgenticNPC 正在启动...");
        getLogger().info("========================================");

        // Phase 1: 配置
        configManager = new ConfigManager(this);

        // Phase 2: 安全组件
        inputSanitizer  = new InputSanitizer(configManager, getLogger());
        itemSafetyGuard = new ItemSafetyGuard(configManager, getLogger());
        actionValidator = new ActionValidator(configManager, getLogger());
        responseParser  = new LLMResponseParser(getLogger());

        // Phase 2: 记忆系统
        initMemorySystem();

        // Phase 3: 存储
        brainStorage = EntityBrainStorageFactory.getInstance(this, getLogger());

        // Phase 4: 通信层
        llmClient      = new LLMClient(configManager, getLogger());
        circuitBreaker = new CircuitBreaker(
            configManager.getCircuitBreakerThreshold(),
            configManager.getCircuitBreakerRecoveryMs(), getLogger()
        );
        rateLimiter   = new RateLimiter(configManager);
        promptBuilder = new PromptBuilder(configManager, memoryManager, getLogger());

        // Phase 3 + 4: 调度器
        asyncDispatcher = new AsyncDispatcher(
            rateLimiter, circuitBreaker, llmClient,
            promptBuilder, responseParser, actionValidator,
            itemSafetyGuard, memoryManager, null,
            configManager, this, getLogger()
        );

        chatCollector = new ChatCollector(
            asyncDispatcher, inputSanitizer, configManager, this, getLogger()
        );
        asyncDispatcher.setChatCollector(chatCollector);

        // Phase 3: 注册 Hook
        new HookRegistry(this, brainStorage, chatCollector, getLogger()).registerAll();

        // Phase 5: 执行层
        TeleportGuard teleportGuard = new TeleportGuard(configManager, getLogger());
        PotionGuard   potionGuard   = new PotionGuard(configManager, getLogger());
        actionExecutor = new ActionExecutor(configManager, teleportGuard, potionGuard, getLogger());
        asyncDispatcher.setExecutionCallback(actionExecutor);
        getLogger().info("[执行层] ActionExecutor 已注入");

        // Sprint 2: 审计日志
        if (configManager.isAuditEnabled()) {
            auditLogger = new AuditLogger(configManager, getDataFolder(), getLogger());
            asyncDispatcher.setAuditLogger(auditLogger);
        }

        // Sprint 2: Token 统计
        tokenTracker = new TokenTracker(configManager, getLogger());
        if (memoryRepository instanceof SQLiteRepository sqlite) {
            tokenTracker.setDbConnection(() -> {
                try { return sqlite.getConnection(); }
                catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            });
        }
        asyncDispatcher.setTokenTracker(tokenTracker);

        // 注册命令
        StatsCommand statsCommand = new StatsCommand(tokenTracker);
        BindCommand  bindCmd      = new BindCommand(brainStorage, configManager);
        bindCmd.setStatsCommand(statsCommand);
        getCommand("agenticnpc").setExecutor(bindCmd);
        getCommand("agenticnpc").setTabCompleter(bindCmd);

        // 注册 Listeners
        getServer().getPluginManager().registerEvents(
            new BindPendingListener(brainStorage, this), this);
        getServer().getPluginManager().registerEvents(chatCollector, this);

        // 启动完成
        getLogger().info("========================================");
        getLogger().info("  AgenticNPC 启动完成！（Sprint 2）");
        getLogger().info("  LLM 端点: " + configManager.getLLMEndpoint());
        getLogger().info("  模型:     " + configManager.getLLMModel());
        getLogger().info("  服务器ID: " + configManager.getServerId());
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        if (auditLogger  != null) auditLogger.shutdown();
        if (tokenTracker != null) tokenTracker.shutdown();
        if (memoryManager != null) memoryManager.shutdown();
        EntityBrainStorageFactory.reset();
        getLogger().info("[AgenticNPC] 已安全关闭。");
    }

    private void initMemorySystem() {
        memoryRepository = new SQLiteRepository(this, configManager, getLogger());
        memoryManager    = new MemoryManager(memoryRepository, configManager, getLogger());
        getLogger().info("[记忆] 记忆系统初始化完成");
    }

    // ---- Getters ----
    public static AgenticNPCPlugin getInstance()      { return instance; }
    public ConfigManager      getConfigManager()       { return configManager; }
    public EntityBrainStorage getBrainStorage()        { return brainStorage; }
    public MemoryManager      getMemoryManager()       { return memoryManager; }
    public AsyncDispatcher    getAsyncDispatcher()     { return asyncDispatcher; }
    public ChatCollector      getChatCollector()       { return chatCollector; }
}
