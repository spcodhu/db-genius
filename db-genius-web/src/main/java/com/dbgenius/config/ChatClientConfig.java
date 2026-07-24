package com.dbgenius.config;

import com.dbgenius.agent.ReasoningChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChatClientConfig {

    /**
     * 显式定义（替代自动配置），保证与 {@link ReasoningChatModel} 并存时注入无歧义。
     * 供 simple chat、意图分类等非工具调用流程使用。
     */
    @Bean
    @Primary
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi, OpenAiChatProperties chatProperties,
                                           ToolCallingManager toolCallingManager) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .toolCallingManager(toolCallingManager)
                .defaultOptions(chatProperties.getOptions())
                .build();
    }

    /**
     * 支持 reasoning_content 回传的模型，供 DbSql/DbWorkflow/DbCompare 三个
     * 工具调用 Agent 使用（DeepSeek thinking 模式的硬性要求）。
     */
    @Bean
    public ReasoningChatModel reasoningChatModel(OpenAiApi openAiApi, OpenAiChatModel openAiChatModel,
                                                 OpenAiChatProperties chatProperties,
                                                 ToolCallingManager toolCallingManager) {
        return new ReasoningChatModel(openAiApi, openAiChatModel, chatProperties, toolCallingManager);
    }

    @Bean
    @Primary
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    public ChatClient agentChatClient(ReasoningChatModel reasoningChatModel) {
        return ChatClient.builder(reasoningChatModel).build();
    }
}
