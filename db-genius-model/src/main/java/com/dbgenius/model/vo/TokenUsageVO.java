package com.dbgenius.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单轮会话的 token 用量快照，经 SSE {@code usage} 事件下发给前端。
 *
 * <p>口径说明：{@code contextTokens} 取本轮最后一次 LLM 调用的 prompt_tokens，
 * 即供应商对实际发出完整 prompt 的权威计数，作为当前上下文窗口占用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageVO {

    /** 本轮提示词 token 合计（所有 LLM 调用累加） */
    private long promptTokens;

    /** 本轮输出 token 合计 */
    private long completionTokens;

    /** 本轮 token 总消耗 */
    private long totalTokens;

    /** 当前上下文占用（最后一次调用的 prompt_tokens） */
    private int contextTokens;

    /** 本轮 LLM 调用次数（含意图分类、多步 think、summary） */
    private int callCount;

    /** 会话累计 token 消耗（持久化后） */
    private Long conversationTotalTokens;

    /** 当前模型最大上下文窗口，未知为 null */
    private Integer contextWindow;
}
