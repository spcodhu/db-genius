package com.dbgenius.agent.compress;

import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.vo.CompressResultVO;
import com.dbgenius.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 空实现压缩策略：不执行任何压缩，直接返回 compressed=false。
 *
 * <p>作为 {@code db-genius.context.auto-compress.strategy} 配置的可选值保留，
 * 用于在真实压缩策略（{@link SummaryContextCompressor}）出现异常时一键回退，
 * 或显式关闭压缩能力时使用，并非"唯一实现"。
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
    public CompressResultVO compress(Long conversationId, Long userId, CompressOptions options) {
        Conversation conversation = conversationService.getById(conversationId);
        Integer beforeTokens = conversation != null ? conversation.getContextTokens() : null;
        return CompressResultVO.builder()
                .conversationId(conversationId)
                .compressed(false)
                .beforeTokens(beforeTokens)
                .afterTokens(beforeTokens)
                .summaryMessageId(null)
                .message("当前压缩策略为 noop，未执行任何压缩")
                .build();
    }
}
