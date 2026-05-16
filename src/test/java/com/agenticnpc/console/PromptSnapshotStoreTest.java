package com.agenticnpc.console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptSnapshotStoreTest {

    private PromptSnapshotStore.PromptSnapshot makeSnapshot(String player, String input) {
        return new PromptSnapshotStore.PromptSnapshot(
            "test-trace", System.currentTimeMillis(), player, "uuid-" + player,
            "test_brain", "TestNPC",
            "system prompt", "history", input,
            "{\"dialogue\":\"hello\"}", "NONE",
            100, 50, 150,
            // breakdown
            50, 10, 20, 5, 15, 10, 40, 30, 8,
            "NONE",
            "current", 100
        );
    }

    @Test
    @DisplayName("空存储返回空列表")
    void emptyStoreReturnsEmpty() {
        PromptSnapshotStore store = new PromptSnapshotStore();
        assertTrue(store.getRecent().isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("添加后可读取")
    void addThenRetrieve() {
        PromptSnapshotStore store = new PromptSnapshotStore();
        store.add(makeSnapshot("Steve", "你好"));

        List<PromptSnapshotStore.PromptSnapshot> list = store.getRecent();
        assertEquals(1, list.size());
        assertEquals("Steve", list.get(0).playerName());
        assertEquals("你好", list.get(0).userInput());
    }

    @Test
    @DisplayName("固定容量环形缓冲：超出容量后覆盖旧数据")
    void ringBufferOverwrite() {
        PromptSnapshotStore store = new PromptSnapshotStore(3);

        store.add(makeSnapshot("A", "msg1"));
        store.add(makeSnapshot("B", "msg2"));
        store.add(makeSnapshot("C", "msg3"));
        store.add(makeSnapshot("D", "msg4")); // 覆盖 A

        assertEquals(3, store.size());
        List<PromptSnapshotStore.PromptSnapshot> list = store.getRecent(10);

        assertEquals("D", list.get(0).playerName());
        assertEquals("msg4", list.get(0).userInput());
        assertEquals("C", list.get(1).playerName());
        assertEquals("B", list.get(2).playerName());
    }

    @Test
    @DisplayName("默认容量为 100，getRecent 限制 50")
    void defaultCapacity100() {
        PromptSnapshotStore store = new PromptSnapshotStore();

        for (int i = 0; i < 120; i++) {
            store.add(makeSnapshot("P" + i, "msg" + i));
        }

        assertEquals(100, store.size());
        // getRecent() 默认返回最近 50 条
        assertEquals(50, store.getRecent().size());
        // 使用自定义 limit 可以获取全部 100 条
        assertEquals(100, store.getRecent(100).size());
    }

    @Test
    @DisplayName("getById 按索引访问（0 = 最新）")
    void getByIdAccess() {
        PromptSnapshotStore store = new PromptSnapshotStore(10);

        store.add(makeSnapshot("A", "old"));
        store.add(makeSnapshot("B", "new"));

        assertEquals("B", store.getById(0).playerName()); // 最新
        assertEquals("A", store.getById(1).playerName()); // 次新
        assertNull(store.getById(2)); // 超出
        assertNull(store.getById(-1)); // 负索引
    }

    @Test
    @DisplayName("getRecent(limit) 限制返回数量")
    void limitResultCount() {
        PromptSnapshotStore store = new PromptSnapshotStore(20);

        for (int i = 0; i < 10; i++) {
            store.add(makeSnapshot("P" + i, "msg" + i));
        }

        assertEquals(5, store.getRecent(5).size());
    }

    @Test
    @DisplayName("Prompt 快照包含完整字段")
    void snapshotFieldsComplete() {
        PromptSnapshotStore store = new PromptSnapshotStore();
        store.add(new PromptSnapshotStore.PromptSnapshot(
            "abc123def456", 1234567890L, "Steve", "uuid-steve",
            "brain1", "NPC",
            "system", "history", "input",
            "output", "GIVE_ITEM",
            100, 50, 150,
            // breakdown
            50, 10, 20, 5, 15, 10, 40, 30, 8,
            "BRACKET_EXTRACTION",
            "slim-v1", 50
        ));

        PromptSnapshotStore.PromptSnapshot snap = store.getById(0);
        assertNotNull(snap);
        assertEquals("abc123def456", snap.traceId());
        assertEquals(1234567890L, snap.timestampMs());
        assertEquals("Steve", snap.playerName());
        assertEquals("uuid-steve", snap.playerUuid());
        assertEquals("brain1", snap.brainId());
        assertEquals("NPC", snap.brainName());
        assertEquals("system", snap.systemPrompt());
        assertEquals("history", snap.history());
        assertEquals("input", snap.userInput());
        assertEquals("output", snap.llmOutput());
        assertEquals("GIVE_ITEM", snap.finalAction());
        assertEquals(100, snap.promptTokens());
        assertEquals(50, snap.completionTokens());
        assertEquals(150, snap.totalTokens());
        assertEquals("slim-v1", snap.variant());
        assertEquals(50, snap.fixedTokenCost());
    }
}
