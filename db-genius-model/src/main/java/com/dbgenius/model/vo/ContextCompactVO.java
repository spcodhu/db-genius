package com.dbgenius.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE {@code context_compact} 事件的载荷：把单轮内的上下文压缩过程暴露给前端，
 * 便于以「工具卡片」形态展示，避免压缩期间的莫名等待。
 *
 * <p>token 数为 {@code TokenEstimator} 的本地近似估算，非计费口径。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContextCompactVO {

    /** start（开始压缩，仅耗时策略下发）｜end（压缩完成） */
    private String phase;

    /** elide（确定性观测遮蔽，毫秒级）｜summarize（LLM 摘要，秒级） */
    private String tier;

    /** 已本地化的展示文案 */
    private String message;

    /** 压缩前估算 token 数 */
    private Integer beforeTokens;

    /** 压缩后估算 token 数（phase=start 时为空） */
    private Integer afterTokens;

    /** 受影响的单元数：遮蔽的观测条数 / 折叠的步骤数（phase=start 时为空） */
    private Integer affectedUnits;
}
