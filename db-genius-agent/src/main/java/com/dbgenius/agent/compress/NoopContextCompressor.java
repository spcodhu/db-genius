package com.dbgenius.agent.compress;

import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.vo.CompressResultVO;
import com.dbgenius.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 空实现压缩策略：本轮仅搭建骨架，不执行任何压缩，下一轮实现真实策略。
 */
@Component
@RequiredArgsConstructor
public class NoopContextCompressor implements ContextCompressor {

    public static final String CODE = "noop";

    private final ConversationService conversationService;

    @Override
    public String strategyCode() {
        return CODE;
    }

    @Override
    public CompressResultVO compress(Long conversationId, CompressOptions options) {
        Conversation conversation = conversationService.getById(conversationId);
        Integer beforeTokens = conversation != null ? conversation.getContextTokens() : null;
        return CompressResultVO.builder()
                .conversationId(conversationId)
                .compressed(false)
                .beforeTokens(beforeTokens)
                .afterTokens(beforeTokens)
                .summaryMessageId(null)
                .message("上下文压缩功能即将上线，敬请期待")
                .build();
    }
}
