package com.dbgenius.agent.compress;

/**
 * 单轮内上下文压缩的过程回调：让「正在压缩」这件事对前端可见，消灭用户侧的莫名等待。
 *
 * <p>压缩的<b>触发时机由 token 估算确定性决定</b>，不做成 LLM 可调用的 Tool——那会让模型
 * 该压不压、不该压乱压，还要多付一次 round-trip，与降低时延的目标相悖。这里只是把压缩过程
 * 以「工具卡片」形态的 SSE 事件暴露给前端，成本为零。</p>
 *
 * <p>Tier-1 确定性遮蔽是毫秒级的，只回调 end；Tier-3 LLM 摘要是秒级的，start/end 都回调。</p>
 */
public interface ContextCompactListener {

    /** 策略标识：确定性观测遮蔽（毫秒级，零 LLM 调用） */
    String TIER_ELIDE = "elide";

    /** 策略标识：LLM 摘要（秒级，需要一次额外的模型调用） */
    String TIER_SUMMARIZE = "summarize";

    /** 压缩开始（仅耗时策略回调），用于前端立即展示「正在压缩上下文…」。 */
    default void onCompactStart(String tier) {
    }

    /**
     * 压缩结束。
     *
     * @param tier          策略标识
     * @param beforeTokens  压缩前估算 token 数（TokenEstimator 近似值，非计费口径）
     * @param afterTokens   压缩后估算 token 数
     * @param affectedUnits 受影响的单元数（遮蔽的观测条数 / 折叠的步骤数）
     */
    default void onCompactEnd(String tier, int beforeTokens, int afterTokens, int affectedUnits) {
    }
}
