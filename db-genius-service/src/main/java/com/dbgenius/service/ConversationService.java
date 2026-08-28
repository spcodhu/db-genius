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

    Long saveMessage(Long conversationId, String role, String content, Integer step, String type);

    /**
     * 保存消息并附带思考内容与工具调用记录。
     *
     * @param reasoningContent 模型思考内容（供应商未返回时传 null）
     * @param toolCalls        工具调用记录 JSON 文本（无工具调用时传 null）
     * @return 新消息的自增 id
     */
    Long saveMessage(Long conversationId, String role, String content, Integer step, String type,
                     String reasoningContent, String toolCalls);

    void deleteConversation(Long userId, Long conversationId);

    /**
     * 累加会话 token 消耗并更新当前上下文占用。
     *
     * @param roundTotalTokens 本轮请求消耗的 token 总数
     * @param contextTokens    当前上下文占用（最后一次 LLM 调用的 prompt_tokens）
     * @return 更新后的会话累计 token 数
     */
    long updateTokenUsage(Long conversationId, long roundTotalTokens, int contextTokens);

    /**
     * LLM 上下文用途：仅返回参与对话轮次的用户消息与最终回答
     * （type ∈ user/summary），排除 step/tool 等过程消息，避免上下文膨胀。
     */
    List<Message> getRecentMessages(Long conversationId, int limit);

    /**
     * 上下文压缩用途：与 {@link #getRecentMessages} 相同的过滤条件
     * （type ∈ user/summary，或 null），但不限制条数，按时间正序返回该会话
     * 全部"可进入上下文"的消息，供压缩策略判断待摘要范围。
     */
    List<Message> getInContextMessages(Long conversationId);

    /**
     * 上下文压缩用途：批量把指定消息标记为已压缩（type=compressed），
     * 使其后续被 {@link #getRecentMessages}/{@link #getInContextMessages} 的过滤条件天然排除。
     */
    void markMessagesCompressed(List<Long> messageIds);
}
