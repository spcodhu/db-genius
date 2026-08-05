package com.dbgenius.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.vo.ConversationVO;

import java.util.List;

public interface ConversationService extends IService<Conversation> {

    ConversationVO createConversation(Long userId, String title, String type, String dbConfigIds);

    List<ConversationVO> listConversations(Long userId);

    /** 前端展示：返回会话全部消息（含 step/tool 等过程消息） */
    List<Message> getMessages(Long conversationId);

    void saveMessage(Long conversationId, String role, String content, Integer step, String type);

    /**
     * 保存消息并附带思考内容与工具调用记录。
     *
     * @param reasoningContent 模型思考内容（供应商未返回时传 null）
     * @param toolCalls        工具调用记录 JSON 文本（无工具调用时传 null）
     */
    void saveMessage(Long conversationId, String role, String content, Integer step, String type,
                     String reasoningContent, String toolCalls);

    void deleteConversation(Long userId, Long conversationId);

    /**
     * LLM 上下文用途：仅返回参与对话轮次的用户消息与最终回答
     * （type ∈ user/summary），排除 step/tool 等过程消息，避免上下文膨胀。
     */
    List<Message> getRecentMessages(Long conversationId, int limit);
}
