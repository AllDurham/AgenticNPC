package com.agenticnpc.dispatch.pipeline.stages;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.context.PromptBuilder;
import com.agenticnpc.dispatch.pipeline.PipelineContext;
import com.agenticnpc.dispatch.pipeline.PipelineStage;
import com.agenticnpc.dispatch.pipeline.PipelineStageType;
import com.agenticnpc.dispatch.pipeline.StageException;
import com.agenticnpc.model.PromptPackage;

import java.util.logging.Logger;

/**
 * PromptStage：构建 PromptPackage + 设置 variant。
 * 失败时抛 StageException（真异常）。
 */
public class PromptStage implements PipelineStage {

    private final PromptBuilder  promptBuilder;
    private final ConfigManager  configManager;
    private final Logger         logger;

    public PromptStage(PromptBuilder promptBuilder, ConfigManager configManager, Logger logger) {
        this.promptBuilder = promptBuilder;
        this.configManager = configManager;
        this.logger        = logger;
    }

    @Override
    public String name() { return "PromptStage"; }

    @Override
    public PipelineStageType type() { return PipelineStageType.PROMPT; }

    @Override
    public void execute(PipelineContext ctx) {
        try {
            ctx.variant = configManager.getPromptVariant();
            PromptBuilder.PromptBuildResult result = promptBuilder.buildWithBreakdown(ctx.event);
            ctx.promptPackage = result.pkg();
            ctx.breakdown = result.breakdown();
        } catch (Exception e) {
            logger.warning(String.format("[%s][PromptStage] Prompt 构建失败: %s", ctx.traceId, e.getMessage()));
            throw new StageException(type(), "Prompt 构建失败: " + e.getMessage(), e);
        }
    }
}
