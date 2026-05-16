package com.agenticnpc.console;

import com.agenticnpc.model.ActionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecentInteractionStoreTest {

    private RecentInteractionStore.InteractionRecord makeRecord(String player, ActionType action) {
        return new RecentInteractionStore.InteractionRecord(
            "test-trace", System.currentTimeMillis(), player, "test_brain", "hello",
            action, 100, 50, false
        );
    }

    @Test
    @DisplayName("空存储返回空列表")
    void emptyStoreReturnsEmpty() {
        RecentInteractionStore store = new RecentInteractionStore();
        assertTrue(store.getRecent().isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("添加后可读取")
    void addThenRetrieve() {
        RecentInteractionStore store = new RecentInteractionStore();
        store.add(makeRecord("Steve", ActionType.NONE));

        List<RecentInteractionStore.InteractionRecord> list = store.getRecent();
        assertEquals(1, list.size());
        assertEquals("Steve", list.get(0).playerName());
    }

    @Test
    @DisplayName("固定容量环形缓冲：超出容量后覆盖旧数据")
    void ringBufferOverwrite() {
        RecentInteractionStore store = new RecentInteractionStore(3);

        store.add(makeRecord("A", ActionType.NONE));
        store.add(makeRecord("B", ActionType.NONE));
        store.add(makeRecord("C", ActionType.NONE));
        store.add(makeRecord("D", ActionType.NONE)); // 覆盖 A

        assertEquals(3, store.size());
        List<RecentInteractionStore.InteractionRecord> list = store.getRecent(10);

        // 最新的在前：D, C, B
        assertEquals("D", list.get(0).playerName());
        assertEquals("C", list.get(1).playerName());
        assertEquals("B", list.get(2).playerName());
    }

    @Test
    @DisplayName("getRecent(maxCount) 限制返回数量")
    void limitResultCount() {
        RecentInteractionStore store = new RecentInteractionStore(20);

        for (int i = 0; i < 10; i++) {
            store.add(makeRecord("P" + i, ActionType.NONE));
        }

        List<RecentInteractionStore.InteractionRecord> list = store.getRecent(3);
        assertEquals(3, list.size());
    }

    @Test
    @DisplayName("默认容量为 20")
    void defaultCapacity20() {
        RecentInteractionStore store = new RecentInteractionStore();

        for (int i = 0; i < 25; i++) {
            store.add(makeRecord("P" + i, ActionType.NONE));
        }

        assertEquals(20, store.size());
        assertEquals(20, store.getRecent().size());

        // 最新的应该是 P24
        assertEquals("P24", store.getRecent().get(0).playerName());
    }

    @Test
    @DisplayName("线程安全：并发写入不丢数据")
    void concurrentWrite() throws InterruptedException {
        RecentInteractionStore store = new RecentInteractionStore(100);

        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    store.add(makeRecord("T" + threadId + "_" + i, ActionType.NONE));
                }
            });
            threads[t].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(100, store.size());
    }
}
