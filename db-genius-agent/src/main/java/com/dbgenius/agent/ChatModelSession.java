package com.dbgenius.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 一次会话期间的模型组合。
 *
 * @param chatModel      普通调用用（simple chat、intent classify），
 *                       开启 token 统计时为 UsageTrackingChatModel 装饰器
 * @param reasoningModel Agent 工具调用（reasoning_content 回传）与流式总结用
 * @param chatClient     包装 chatModel 的 ChatClient
 */
public record ChatModelSession(
        ChatModel chatModel,
        ReasoningChatModel reasoningModel,
        ChatClient chatClient) {
}
