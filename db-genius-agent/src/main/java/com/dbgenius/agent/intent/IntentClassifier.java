package com.dbgenius.agent.intent;

import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.IntentClassificationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 基于 LLM 的意图分类器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifier {

    private final ChatClient chatClient;

    public static final double CONFIDENCE_THRESHOLD = 0.7;

    public IntentClassificationResult classify(String userMessage,
                                               List<Message> recentHistory,
                                               ChatContext context) {
        String systemPrompt = buildSystemPrompt(context);
        String historyText = formatHistory(recentHistory);

        String userPrompt = historyText + "\n\n当前用户消息: " + userMessage;

        log.debug("Classifying intent for message: {}", userMessage);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                // 意图分类是每次对话的前置内部调用，thinking 模式只会增加延迟，
                // 逐调用关闭（runtime extraBody 覆盖默认配置）
                .options(OpenAiChatOptions.builder()
                        .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                        .build())
                .call()
                .entity(IntentClassificationResult.class);
    }

    private String buildSystemPrompt(ChatContext context) {
        return """
                你是一个意图分类器。根据用户的消息和对话历史，判断用户意图并返回 JSON。

                ## 意图类型定义

                ### simple_chat
                - 定义：不涉及数据库操作的简单对话（问候、闲聊、概念解释、通用知识问答）
                - 示例："你好"、"什么是索引？"、"谢谢"
                - 边界：即使提到数据库概念，只要不需要实际执行查询就属于此类

                ### sql_query
                - 定义：需要连接数据库执行 SQL 查询、数据分析、表结构查看
                - 示例："查一下用户表有多少条数据"、"帮我看看最近7天的订单"、"user 表的结构是什么"
                - 前置条件：用户需已选择数据库连接
                - 边界：单次或少量 SQL 操作，不涉及文件处理

                ### workflow
                - 定义：复杂的多步骤数据库操作，通常涉及文件上传、批量处理、数据导入导出
                - 示例："把这个 Excel 的数据导入到用户表"、"根据这份文件批量更新价格"
                - 前置条件：通常伴随文件上传，或明确描述多步骤流程
                - 边界：涉及文件处理或明确的多步骤操作流程

                ### db_compare
                - 定义：对比两个数据库的结构差异，生成部署 SQL
                - 示例："对比预发和测试环境的数据库"、"帮我看看这两个库的差异"、"生成部署SQL"
                - 前置条件：需要两个数据库连接（pre 和 test）
                - 边界：涉及跨库结构对比

                ## 当前请求上下文
                - 用户是否已选择数据库: %s
                - 用户是否上传了文件: %s
                - 用户是否提供了对比数据库: %s

                ## 输出格式
                返回 JSON，字段如下：
                {
                  "intent": "枚举值之一（simple_chat/sql_query/workflow/db_compare）",
                  "confidence": 0.0-1.0,
                  "reasoning": "判断依据（简洁）",
                  "needsClarification": false
                }

                规则：
                1. 如果上下文缺少前置条件（如需要数据库但用户未选择），设 needsClarification=true。
                2. 如果真正无法确定意图，设 confidence < 0.7 且 needsClarification=true。
                3. 对话历史中如果连续是同一类型的操作，当前模糊消息应倾向于延续该意图。
                4. 不要返回枚举名（如 SQL_QUERY），必须返回 code 值（如 sql_query）。
                """.formatted(context.hasDbConfig(), context.hasFiles(), context.hasCompareConfig());
    }

    private String formatHistory(List<Message> recentHistory) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return "（无历史对话）";
        }
        StringBuilder sb = new StringBuilder("## 最近对话历史\n\n");
        for (Message message : recentHistory) {
            String role = "user".equals(message.getRole()) ? "用户" : "助手";
            sb.append(role).append(": ").append(message.getContent()).append("\n");
        }
        return sb.toString();
    }
}
