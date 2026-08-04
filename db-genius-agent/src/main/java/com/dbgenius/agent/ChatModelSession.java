package com.dbgenius.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;

/**
 * 一次会话期间的模型组合。
 *
 * @param chatModel        普通调用用（simple chat、intent classify、summary）
 * @param reasoningModel   Agent 工具调用用（reasoning_content 回传）
 * @param chatClient       包装 chatModel 的 ChatClient
 * @param agentChatClient  包装 reasoningModel 的 ChatClient
 */
public record ChatModelSession(
        OpenAiChatModel chatModel,
        ReasoningChatModel reasoningModel,
        ChatClient chatClient,
        ChatClient agentChatClient) {
}
