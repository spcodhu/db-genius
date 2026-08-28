package com.dbgenius.agent.compress;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.vo.CompressResultVO;
import com.dbgenius.service.ConversationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 压缩编排默认实现：启动时收集所有 {@link ContextCompressor} Bean 建立策略表
 * （与 IntentHandlerRegistry 同模式）。默认策略由
 * {@code db-genius.context.auto-compress.strategy} 配置（默认 "summary"），
 * 未命中配置策略或未提供任何 Bean 时回退到第一个可用策略。
 */
@Service
public class DefaultContextCompressService implements ContextCompressService {

    private final ConversationService conversationService;
    private final Map<String, ContextCompressor> compressors;

    @Value("${db-genius.context.auto-compress.strategy:summary}")
    private String defaultStrategy;

    public DefaultContextCompressService(ConversationService conversationService,
                                         List<ContextCompressor> compressorList) {
        this.conversationService = conversationService;
        this.compressors = compressorList.stream()
                .collect(Collectors.toMap(ContextCompressor::strategyCode, Function.identity()));
    }

    @Override
    public CompressResultVO compress(Long userId, Long conversationId, CompressOptions options) {
        Conversation conversation = conversationService.getById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(404, "Conversation not found");
        }
        ContextCompressor compressor = compressors.getOrDefault(
                defaultStrategy, compressors.getOrDefault(
                        NoopContextCompressor.CODE, compressors.values().iterator().next()));
        return compressor.compress(conversationId, userId, options);
    }
}
