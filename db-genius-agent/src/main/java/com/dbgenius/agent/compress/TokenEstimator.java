package com.dbgenius.agent.compress;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.messages.Message;

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

    /** 估算一组 Spring AI {@link Message} 的 token 数总和（取每条消息的文本内容）。 */
    public static int estimate(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            total += estimate(message.getText());
        }
        return total;
    }
}
