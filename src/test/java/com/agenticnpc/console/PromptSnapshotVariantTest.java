package com.agenticnpc.console;

import com.agenticnpc.model.PromptTokenBreakdown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptSnapshotVariantTest {

    @Test
    @DisplayName("PromptSnapshot 包含 variant 和 fixedTokenCost 字段")
    void snapshotHasVariantFields() {
        PromptSnapshotStore.PromptSnapshot snap = new PromptSnapshotStore.PromptSnapshot(
            "trace1", 1234567890L, "Steve", "uuid",
            "brain", "NPC", "system", "history", "input",
            "output", "NONE",
            100, 50, 150,
            50, 10, 20, 5, 15, 10, 40, 30, 8,
            "NONE",
            "slim-v1",
            100
        );

        assertEquals("slim-v1", snap.variant());
        assertEquals(100, snap.fixedTokenCost());
    }

    @Test
    @DisplayName("fixedCost = formatTokens + actionTokens")
    void fixedCostComputation() {
        PromptTokenBreakdown b = new PromptTokenBreakdown(
            100, 20, 30, 5, 15, 10, 40, 50, 8, 278);
        assertEquals(50, b.fixedCost()); // 40 + 10
    }

    @Test
    @DisplayName("PromptTokenBreakdown.empty().fixedCost() = 0")
    void emptyFixedCostIsZero() {
        assertEquals(0, PromptTokenBreakdown.empty().fixedCost());
    }

    @Test
    @DisplayName("PromptSnapshotStore 存取 variant 字段")
    void storeRetrievesVariant() {
        PromptSnapshotStore store = new PromptSnapshotStore();
        store.add(new PromptSnapshotStore.PromptSnapshot(
            "trace2", System.currentTimeMillis(), "Alex", "uuid2",
            "brain", "NPC", "sys", "hist", "inp",
            "out", "NONE", 80, 40, 120,
            40, 10, 15, 5, 10, 8, 30, 25, 6,
            "NONE", "slim-v1", 36
        ));

        assertEquals("slim-v1", store.getById(0).variant());
        assertEquals(36, store.getById(0).fixedTokenCost());
    }
}
