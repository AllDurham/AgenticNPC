package com.agenticnpc.audit;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.model.ActionType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 审计日志系统。
 *
 * 设计原则：
 * 1. 异步写入：所有日志操作放入队列，独立线程消费，不阻塞主线程
 * 2. 文件轮转：按日期自动切割日志文件（audit-2024-01-01.log）
 * 3. 敏感信息脱敏：API Key 等敏感字段不写入日志
 * 4. MySQL 双写：MySQL 模式下同时写入 audit_log 表
 */
public class AuditLogger {

    public enum EventType {
        DIALOGUE_START,
        DIALOGUE_SUCCESS,
        DIALOGUE_FALLBACK,
        ACTION_EXECUTED,
        ACTION_BLOCKED,
        INJECTION_ATTEMPT,
        RATE_LIMITED,
        CIRCUIT_OPEN,
        PARSE_FAILED
    }

    public record AuditEntry(
        long         timestampMs,
        String       playerUuid,
        String       playerName,
        String       brainId,
        EventType    eventType,
        String       input,
        String       output,
        ActionType   actionType,
        String       actionParams,
        String       serverId
    ) {}

    private final BlockingQueue<AuditEntry> queue   = new LinkedBlockingQueue<>(10000);
    private final Thread                    consumer;
    private volatile boolean                running = true;

    private final ConfigManager config;
    private final File          logDir;
    private final Logger        logger;
    private AuditRepository     auditRepository;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

    public AuditLogger(ConfigManager config, File dataFolder, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.logDir = new File(dataFolder, "audit-logs");
        if (!logDir.exists()) logDir.mkdirs();

        this.consumer = new Thread(this::consumeLoop, "AgenticNPC-AuditLogger");
        this.consumer.setDaemon(true);
        this.consumer.start();
        logger.info("[审计] 审计日志系统启动 | 日志目录: " + logDir.getAbsolutePath());
    }

    public void setAuditRepository(AuditRepository repository) {
        this.auditRepository = repository;
    }

    // ---- 公开日志方法（非阻塞）----

    public void logDialogueSuccess(UUID playerId, String playerName,
                                    String brainId, String input,
                                    String output, ActionType actionType,
                                    String actionParams) {
        enqueue(new AuditEntry(
            System.currentTimeMillis(),
            playerId.toString(), playerName, brainId,
            EventType.DIALOGUE_SUCCESS,
            truncate(input, 200), truncate(output, 200),
            actionType, actionParams, config.getServerId()
        ));
    }

    public void logActionBlocked(UUID playerId, String playerName,
                                  String brainId, String reason,
                                  ActionType actionType) {
        enqueue(new AuditEntry(
            System.currentTimeMillis(),
            playerId.toString(), playerName, brainId,
            EventType.ACTION_BLOCKED,
            null, reason, actionType, null, config.getServerId()
        ));
    }

    public void logInjectionAttempt(UUID playerId, String playerName,
                                     String brainId, String rawInput) {
        enqueue(new AuditEntry(
            System.currentTimeMillis(),
            playerId.toString(), playerName, brainId,
            EventType.INJECTION_ATTEMPT,
            truncate(rawInput, 500), null, null, null, config.getServerId()
        ));
    }

    public void logRateLimited(UUID playerId, String playerName, String brainId) {
        enqueue(new AuditEntry(
            System.currentTimeMillis(),
            playerId.toString(), playerName, brainId,
            EventType.RATE_LIMITED,
            null, null, null, null, config.getServerId()
        ));
    }

    public void logParseFailed(UUID playerId, String playerName,
                                String brainId, String rawContent) {
        enqueue(new AuditEntry(
            System.currentTimeMillis(),
            playerId.toString(), playerName, brainId,
            EventType.PARSE_FAILED,
            null, truncate(rawContent, 300), null, null, config.getServerId()
        ));
    }

    public void logCircuitOpen(String brainId, int failureCount) {
        enqueue(new AuditEntry(
            System.currentTimeMillis(),
            "SYSTEM", "SYSTEM", brainId,
            EventType.CIRCUIT_OPEN,
            null, "熔断器打开，连续失败次数: " + failureCount,
            null, null, config.getServerId()
        ));
    }

    /**
     * 启动审计日志自动清理定时任务。
     * 每天执行一次，删除超过 retention-days 的日志文件。
     * 必须在主类 onEnable 中、audit 初始化之后调用。
     */
    public void startCleanupTask(org.bukkit.plugin.Plugin plugin) {
        long intervalTicks = 20L * 60 * 60 * 24; // 24 小时
        int retentionDays = config.getAuditRetentionDays();

        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                cleanupOldLogs(retentionDays);
            } catch (Exception e) {
                logger.warning("[审计] 日志自动清理失败: " + e.getMessage());
            }
        }, intervalTicks, intervalTicks); // 首次执行延迟 24 小时

        logger.info("[审计] 审计日志自动清理已启动 | 保留天数: " + retentionDays);
    }

    private void cleanupOldLogs(int retentionDays) {
        if (!logDir.exists()) return;

        long cutoff = System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1000;
        String cutoffDate = DATE_FORMAT.format(new Date(cutoff));

        File[] files = logDir.listFiles((dir, name) ->
            name.startsWith("audit-") && name.endsWith(".log")
        );
        if (files == null) return;

        int deleted = 0;
        for (File file : files) {
            // 文件名格式: audit-2024-01-01.log
            String dateStr = file.getName()
                .replace("audit-", "")
                .replace(".log", "");
            if (dateStr.compareTo(cutoffDate) < 0) {
                if (file.delete()) deleted++;
            }
        }

        if (deleted > 0) {
            logger.info("[审计] 自动清理完成 | 删除 " + deleted + " 个过期日志文件");
        }
    }

    public void shutdown() {
        running = false;
        consumer.interrupt();
        try { consumer.join(5000); } catch (InterruptedException ignored) {}
        logger.info("[审计] 审计日志系统已关闭");
    }

    // ---- 内部实现 ----

    private void enqueue(AuditEntry entry) {
        if (!queue.offer(entry)) {
            logger.warning("[审计] 审计队列已满，丢弃日志条目！");
        }
    }

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                AuditEntry entry = queue.poll(1, TimeUnit.SECONDS);
                if (entry == null) continue;
                writeToFile(entry);
                if (auditRepository != null) {
                    auditRepository.save(entry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.warning("[审计] 写入审计日志失败: " + e.getMessage());
            }
        }
    }

    private void writeToFile(AuditEntry entry) {
        String dateStr = DATE_FORMAT.format(new Date(entry.timestampMs()));
        File logFile   = new File(logDir, "audit-" + dateStr + ".log");
        String line    = buildLogLine(entry);

        try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(line);
            bw.newLine();
        } catch (IOException e) {
            logger.warning("[审计] 文件写入失败: " + e.getMessage());
        }
    }

    private String buildLogLine(AuditEntry e) {
        String time   = TIME_FORMAT.format(new Date(e.timestampMs()));
        String input  = e.input()  != null ? e.input()  : "-";
        String output = e.output() != null ? e.output() : "-";
        String action = e.actionType() != null ? e.actionType().name() : "NONE";

        return String.format("[%s] [%s] [%s/%s] IN: %s | OUT: %s | ACTION: %s | SERVER: %s",
            time, e.eventType().name(), e.playerName(), e.brainId(),
            input, output, action, e.serverId());
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "[truncated]" : s;
    }
}
