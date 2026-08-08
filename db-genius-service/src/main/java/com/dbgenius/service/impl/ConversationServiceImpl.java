package com.dbgenius.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.mapper.ConversationMapper;
import com.dbgenius.mapper.MessageMapper;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.vo.ConversationVO;
import com.dbgenius.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation> implements ConversationService {

    private final MessageMapper messageMapper;

    @Override
    public ConversationVO createConversation(Long userId, String title, String type, String dbConfigIds) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(title);
        conversation.setType(type);
        conversation.setDbConfigIds(dbConfigIds);
        save(conversation);
        return toVO(conversation);
    }

    @Override
    public List<ConversationVO> listConversations(Long userId) {
        return list(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getUpdatedAt))
                .stream().map(this::toVO).toList();
    }

    @Override
    public List<Message> getMessages(Long conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getCreatedAt));
    }

    @Override
    public void saveMessage(Long conversationId, String role, String content, Integer step, String type) {
        saveMessage(conversationId, role, content, step, type, null, null);
    }

    @Override
    public void saveMessage(Long conversationId, String role, String content, Integer step, String type,
                            String reasoningContent, String toolCalls) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setStep(step);
        message.setType(type);
        message.setReasoningContent(reasoningContent);
        message.setToolCalls(toolCalls);
        messageMapper.insert(message);
    }

    @Override
    public void deleteConversation(Long userId, Long conversationId) {
        Conversation conversation = getById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(404, "Conversation not found");
        }
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId));
        removeById(conversationId);
    }

    @Override
    public long updateTokenUsage(Long conversationId, long roundTotalTokens, int contextTokens) {
        update(new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .setSql("total_tokens = total_tokens + " + roundTotalTokens)
                .set(Conversation::getContextTokens, contextTokens));
        Conversation conversation = getById(conversationId);
        return conversation != null && conversation.getTotalTokens() != null
                ? conversation.getTotalTokens() : 0L;
    }

    @Override
    public List<Message> getRecentMessages(Long conversationId, int limit) {
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                // 上下文隔离：只取用户消息与最终回答，排除 step/tool 过程消息
                .and(w -> w.in(Message::getType, "user", "summary").or().isNull(Message::getType))
                .orderByDesc(Message::getCreatedAt)
                .last("LIMIT " + limit));
        // 按时间正序返回，便于构造上下文
        return messages.reversed();
    }

    private ConversationVO toVO(Conversation conversation) {
        ConversationVO vo = new ConversationVO();
        vo.setId(conversation.getId());
        vo.setTitle(conversation.getTitle());
        vo.setType(conversation.getType());
        vo.setDbConfigIds(conversation.getDbConfigIds());
        vo.setTotalTokens(conversation.getTotalTokens());
        vo.setContextTokens(conversation.getContextTokens());
        vo.setCreatedAt(conversation.getCreatedAt());
        return vo;
    }
}
