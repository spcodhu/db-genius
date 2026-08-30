package com.dbgenius.agent.compress;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * 本地近似 token 估算工具。
 *
 * <p><b>重要：</b>底层使用 OpenAI {@code cl100k_base} 分词编码，DeepSeek 等其他供应商的实际分词器与此
 * 不完全一致，估算结果只能作为"是否需要压缩""压缩了多少"的近似判断依据，<b>不作为计费口径</b>。
 * 权威的 token 占用仍以 API 回传的 {@code prompt_tokens}（见
 * {@link com.dbgenius.agent.usage.TokenUsageAccumulator}、{@code conversation.contextTokens}）为准。
 */
public final class TokenEstimator {

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    private TokenEstimator() {
    }

    /** 估算一段文本的 token 数；null/空串返回 0。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return ENCODING.countTokens(text);
    }

    /**
     * 估算一组 Spring AI {@link Message} 的 token 数总和。
     *
     * <p><b>注意：</b>不能只取 {@code message.getText()}——{@link ToolResponseMessage#getText()}
     * 恒为空串，而工具结果恰恰是单轮上下文膨胀的最大来源；{@link AssistantMessage} 的
     * {@code tool_calls}（函数名 + arguments JSON）同样会真实进入请求体。两者都必须计入，
     * 否则单轮内压缩的阈值判断会严重低估实际占用而永远不触发。</p>
     */
    public static int estimate(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            total += estimateMessage(message);
        }
        return total;
    }

    /** 估算单条消息的 token 数：正文 + 工具结果 + 工具调用参数。 */
    public static int estimateMessage(Message message) {
        if (message == null) {
            return 0;
        }
        int total = estimate(message.getText());
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                total += estimate(response.name()) + estimate(response.responseData());
            }
        } else if (message instanceof AssistantMessage assistantMessage
                && assistantMessage.getToolCalls() != null) {
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                total += estimate(toolCall.name()) + estimate(toolCall.arguments());
            }
        }
        return total;
    }
}
