package com.agenticnpc.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prompt 快照存储（固定容量环形缓冲区）。
 *
 * 保存最近 N 条 Prompt 决策记录，用于调试 AI 决策过程。
 * 默认容量 100。内存存储，不落盘。
 *
 * 隐私考虑：Prompt 可能包含玩家输入，仅保存在内存中，
 * 不持久化到磁盘，WebConsole 关闭后自动清除。
 */
public class PromptSnapshotStore {

    public record PromptSnapshot(
        String traceId,
        long   timestampMs,
        String playerName,
        String playerUuid,
        String brainId,
        String brainName,
        String systemPrompt,
        String history,
        String userInput,
        String llmOutput,
        String finalAction,
        int    promptTokens,
        int    completionTokens,
        int    totalTokens,
        // v1.4-b: Token breakdown
        int    systemTokens,
        int    profileTokens,
        int    summaryTokens,
        int    emotionTokens,
        int    playerInfoTokens,
        int    actionTokens,
        int    formatTokens,
        int    historyTokens,
        int    userInputTokens,
        // v1.4-b: Fallback type
        String fallbackType,
        // v1.4-c: Prompt variant
        String variant,
        int    fixedTokenCost
    ) {}

    private final PromptSnapshot[] buffer;
    private final int capacity;
    private int head = 0;
    private int size = 0;

    public PromptSnapshotStore() {
        this(100);
    }

    public PromptSnapshotStore(int capacity) {
        this.capacity = capacity;
        this.buffer = new PromptSnapshot[capacity];
    }

    /**
     * 保存一条 Prompt 快照（线程安全）。
     */
    public synchronized void add(PromptSnapshot snapshot) {
        buffer[head] = snapshot;
        head = (head + 1) % capacity;
        if (size < capacity) size++;
    }

    /**
     * 获取最近 N 条快照（最新的在前）。
     */
    public synchronized List<PromptSnapshot> getRecent(int maxCount) {
        if (size == 0) return Collections.emptyList();

        int count = Math.min(maxCount, size);
        List<PromptSnapshot> result = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            int index = (head - 1 - i + capacity) % capacity;
            result.add(buffer[index]);
        }
        return result;
    }

    /**
     * 获取最近 50 条快照（Web 默认展示量）。
     */
    public List<PromptSnapshot> getRecent() {
        return getRecent(50);
    }

    /**
     * 获取指定 ID 的快照（ID = 从新到旧的 0-based 索引）。
     */
    public synchronized PromptSnapshot getById(int id) {
        if (id < 0 || id >= size) return null;
        int index = (head - 1 - id + capacity) % capacity;
        return buffer[index];
    }

    /**
     * 当前存储的快照数。
     */
    public synchronized int size() {
        return size;
    }
}
