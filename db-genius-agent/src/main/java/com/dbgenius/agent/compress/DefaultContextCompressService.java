package com.dbgenius.agent.compress;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.vo.CompressResultVO;
import com.dbgenius.service.ConversationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 压缩编排默认实现：启动时收集所有 {@link ContextCompressor} Bean 建立策略表
 * （与 IntentHandlerRegistry 同模式），当前默认策略为 noop（空实现）。
 */
@Service
public class DefaultContextCompressService implements ContextCompressService {

    private final ConversationService conversationService;
    private final Map<String, ContextCompressor> compressors;

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
                NoopContextCompressor.CODE, compressors.values().iterator().next());
        return compressor.compress(conversationId, options);
    }
}
