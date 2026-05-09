package com.agenticnpc.pipeline;

import com.agenticnpc.model.InteractionEvent;

/**
 * 交互处理管线接口。
 * Layer 1 完成事件标准化后，通过此接口提交给后续层级。
 * 解耦 Hook 层与处理层，便于测试和替换。
 */
public interface InteractionPipeline {
    void submit(InteractionEvent event);
}
