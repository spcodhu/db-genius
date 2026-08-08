package com.dbgenius.agent.compress;

import com.dbgenius.model.vo.CompressResultVO;

/**
 * 上下文压缩策略接口（策略模式）。
 *
 * <p>镜像项目现有 IntentHandler + IntentHandlerRegistry 的自动发现模式：
 * 实现类注册为 Spring Bean，由 {@link ContextCompressService} 按 {@link #strategyCode()}
 * 选择。未来新增真实压缩策略（如 LLM 摘要压缩）只需新增实现类，零侵入。
 *
 * <p>未来契约（与现有 type="summary" 机制融合）：
 * <ul>
 *   <li>压缩产物以 assistant + {@code type="summary"} 消息落库，自动进入后续上下文；</li>
 *   <li>被压缩的旧消息 type 置为 "compressed"，被 getRecentMessages 的
 *       过滤条件（type ∈ user/summary）天然排除，零改动；</li>
 *   <li>保留最近 K 轮消息不压缩。</li>
 * </ul>
 */
public interface ContextCompressor {

    /** 策略编码，如 "noop"、"summary" */
    String strategyCode();

    /**
     * 对指定会话执行上下文压缩。
     *
     * @param conversationId 会话 ID（调用方已完成属主校验）
     * @param options        压缩选项，可为 null
     */
    CompressResultVO compress(Long conversationId, CompressOptions options);
}
