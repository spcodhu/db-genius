package com.dbgenius.agent;

import com.dbgenius.agent.model.AgentState;
import com.dbgenius.model.vo.SseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class ToolCallAgent extends ReActAgent {

    protected final ToolCallback[] availableTools;
    protected final ChatClient chatClient;
    protected final ToolCallingManager toolCallingManager;
    protected final ChatOptions chatOptions;

    private ChatResponse toolCallChatResponse;

    public ToolCallAgent(String name, String systemPrompt, String nextStepPrompt,
                         int maxSteps, ChatClient chatClient, Object... toolObjects) {
        super(name, maxSteps);
        this.systemPrompt = systemPrompt;
        this.nextStepPrompt = nextStepPrompt;
        this.chatClient = chatClient;
        this.availableTools = ToolCallbacks.from(toolObjects);
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
    }

    @Override
    protected void onStepStart(SseEmitter emitter, String userPrompt) throws Exception {
        messageList.add(new UserMessage(userPrompt));
        sendEvent(emitter, SseEvent.of(taskId, 0, "thinking", "Analyzing your request..."));
    }

    /**
     * 执行模型思考
     * @return 返回值表示是否需要执行 tool
     */
    @Override
    protected boolean think(){
        if (nextStepPrompt != null && !nextStepPrompt.isBlank() && currentStep > 1) {
            messageList.add(new UserMessage(nextStepPrompt));
        }

        Prompt prompt = new Prompt(messageList, chatOptions);

        ChatResponse response = chatClient.prompt(prompt)
                .system(systemPrompt)
                .toolCallbacks(availableTools)
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null) {
            log.warn("[{}] Empty response from LLM", name);
            return false;
        }

        this.toolCallChatResponse = response;

        AssistantMessage assistantMessage = response.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();

        String result = assistantMessage.getText();
        log.info("[{}] thinking: {}", name, result);

        if (toolCalls == null || toolCalls.isEmpty()) {
            messageList.add(assistantMessage);
            return false;
        }

        log.info("[{}] selected {} tools: {}", name, toolCalls.size(),
                toolCalls.stream().map(AssistantMessage.ToolCall::name)
                        .collect(Collectors.joining(", ")));
        return true;
    }

    @Override
    protected String act() throws Exception {
        if (toolCallChatResponse == null || !toolCallChatResponse.hasToolCalls()) {
            return "No tools to call.";
        }

        Prompt prompt = new Prompt(messageList, chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        messageList.clear();
        messageList.addAll(toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage)
                toolExecutionResult.conversationHistory().get(toolExecutionResult.conversationHistory().size() - 1);

        // todo 结束标识符在 systemPrompt 里面输入给模型了，这里直接硬编码，后面可以考虑更优雅的方式。
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));

        if (terminateToolCalled) {
            state = AgentState.FINISHED;
            log.info("[{}] Terminate tool called, finishing", name);
        }

        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "Tool " + response.name() + " result: " + response.responseData())
                .collect(Collectors.joining("\n"));

        log.info("[{}] act results: {}", name,
                results.length() > 300 ? results.substring(0, 300) + "..." : results);
        return results;
    }

    private static final String SUMMARY_SYSTEM_PROMPT = """
            You are a concise Markdown summarizer. Your job is to produce the final summary of a database task.

            ## Output Rules
            1. Output ONLY a Markdown document. Do not wrap it in code fences unless you are showing code/SQL.
            2. If the task returned query data (rows/columns), present the results as a Markdown table with a clear header.
               - Use the real column names from the result.
               - Limit the table to at most 100 rows; if there are more, add a note like “（共 N 条，仅展示前 100 条）”.
            3. For non-query tasks (data import, schema changes, database comparison, etc.), use Markdown sections, bullet lists, and code blocks to summarize what was done and the final conclusion.
            4. Keep the summary concise but complete: state what action was taken and the outcome.
            5. Respond in the same language as the user's original request.
            """;

    private static final String SUMMARY_USER_PROMPT_TEMPLATE = """
            Original user request:
            %s

            Based on the task execution history above, generate the final Markdown summary.
            If the history contains query results, output them as a Markdown table.
            """;

    @Override
    protected void onFinish(SseEmitter emitter, String userPrompt) throws Exception {
        String markdown = generateMarkdownSummary(userPrompt);
        sendEvent(emitter, SseEvent.of(taskId, currentStep, "summary", markdown));
        if (summaryCallback != null) {
            try {
                summaryCallback.accept(markdown);
            } catch (Exception e) {
                log.warn("[{}] summary callback failed: {}", name, e.getMessage());
            }
        }
    }

    private String generateMarkdownSummary(String userPrompt) {
        try {
            List<Message> summaryMessages = new ArrayList<>(messageList);
            summaryMessages.add(new UserMessage(SUMMARY_USER_PROMPT_TEMPLATE.formatted(userPrompt)));

            String markdown = chatClient.prompt()
                    .messages(summaryMessages)
                    .system(SUMMARY_SYSTEM_PROMPT)
                    .call()
                    .content();

            if (markdown == null || markdown.isBlank()) {
                return fallbackSummary(userPrompt);
            }
            return markdown;
        } catch (Exception e) {
            log.error("[{}] Failed to generate Markdown summary", name, e);
            return fallbackSummary(userPrompt);
        }
    }

    private String fallbackSummary(String userPrompt) {
        String lastResult = messageList.stream()
                .filter(m -> m instanceof AssistantMessage || m instanceof ToolResponseMessage)
                .reduce((first, second) -> second)
                .map(Message::getText)
                .orElse("Task completed.");
        return "## Summary\n\n" + lastResult;
    }
}
