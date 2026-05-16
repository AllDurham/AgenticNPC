package com.agenticnpc.console;

import com.agenticnpc.model.ActionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 最近对话记录存储（固定容量环形缓冲区）。
 *
 * 线程安全，容量默认 20。
 * 由 AsyncDispatcher 在每次成功/失败后调用 add()，
 * WebConsole 通过 getRecent() 读取。
 */
public class RecentInteractionStore {

    public record InteractionRecord(
        String    traceId,
        long      timestampMs,
        String    playerName,
        String    brainId,
        String    inputSummary,
        ActionType actionType,
        int       totalTokens,
        long      latencyMs,
        boolean   fallback
    ) {}

    private final InteractionRecord[] buffer;
    private final int capacity;
    private int head = 0;   // 下一个写入位置
    private int size = 0;   // 当前元素数量

    public RecentInteractionStore() {
        this(20);
    }

    public RecentInteractionStore(int capacity) {
        this.capacity = capacity;
        this.buffer = new InteractionRecord[capacity];
    }

    /**
     * 添加一条交互记录（线程安全）。
     */
    public synchronized void add(InteractionRecord record) {
        buffer[head] = record;
        head = (head + 1) % capacity;
        if (size < capacity) size++;
    }

    /**
     * 获取最近 N 条记录（最新的在前）。
     */
    public synchronized List<InteractionRecord> getRecent(int maxCount) {
        if (size == 0) return Collections.emptyList();

        int count = Math.min(maxCount, size);
        List<InteractionRecord> result = new ArrayList<>(count);

        // 从最新的开始回溯
        for (int i = 0; i < count; i++) {
            int index = (head - 1 - i + capacity) % capacity;
            result.add(buffer[index]);
        }
        return result;
    }

    /**
     * 获取最近 20 条记录。
     */
    public List<InteractionRecord> getRecent() {
        return getRecent(20);
    }

    /**
     * 当前存储的记录数。
     */
    public synchronized int size() {
        return size;
    }
}
