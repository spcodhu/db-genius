package com.dbgenius.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.vo.ConversationVO;

import java.util.List;

public interface ConversationService extends IService<Conversation> {

    ConversationVO createConversation(Long userId, String title, String type, String dbConfigIds);

    List<ConversationVO> listConversations(Long userId);

    List<Message> getMessages(Long conversationId);

    void saveMessage(Long conversationId, String role, String content, Integer step, String type);

    void deleteConversation(Long userId, Long conversationId);

    List<Message> getRecentMessages(Long conversationId, int limit);
}
