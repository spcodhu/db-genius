package com.dbgenius.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文压缩结果。{@code compressed} 反映本次是否实际执行了压缩（消息数不足阈值或摘要调用失败等
 * 情况下为 false，见 {@code SummaryContextCompressor}/{@code NoopContextCompressor}）。
 * {@code beforeTokens}/{@code afterTokens} 为近似估算值，非计费口径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompressResultVO {

    private Long conversationId;

    /** 是否实际执行了压缩 */
    private boolean compressed;

    /** 压缩前上下文占用 token */
    private Integer beforeTokens;

    /** 压缩后上下文占用 token（未压缩时与 before 相同） */
    private Integer afterTokens;

    /** 压缩产物（type="summary"）消息 id，未压缩为 null */
    private Long summaryMessageId;

    /** 给用户的结果说明 */
    private String message;
}
