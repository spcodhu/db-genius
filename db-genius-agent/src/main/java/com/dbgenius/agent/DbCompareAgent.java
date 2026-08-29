package com.dbgenius.agent;

import com.dbgenius.agent.prompt.PromptTemplateLoader;
import com.dbgenius.agent.tool.DbCompareTool;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.model.vo.SseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Locale;
import java.util.Map;

@Slf4j
public class DbCompareAgent extends ToolCallAgent {

    private static final String PROMPT_TEMPLATE = "db-compare-agent-system";

    public DbCompareAgent(ReasoningChatModel reasoningChatModel,
                          DbCompareTool dbCompareTool, SqlExecuteTool sqlExecuteTool,
                          TerminateTool terminateTool, String preDbDoc, String testDbDoc,
                          Locale locale) {
        super(
                "DbCompareAgent",
                buildSystemPrompt(preDbDoc, testDbDoc, locale),
                "Continue the comparison. If structures have been compared, analyze the differences and generate the deployment SQL. When done, call doTerminate.",
                15,
                reasoningChatModel,
                dbCompareTool,
                sqlExecuteTool,
                terminateTool
        );
        setLocale(locale);
    }

    @Override
    protected void onStepStart(SseEmitter emitter, String userPrompt) throws Exception {
        super.onStepStart(emitter, userPrompt);
        sendEvent(emitter, SseEvent.of(taskId, 0, "thinking",
                "Starting database comparison analysis. Will compare table structures between pre and test environments."));
    }

    private static String buildSystemPrompt(String preDbDoc, String testDbDoc, Locale locale) {
        String template = PromptTemplateLoader.load(PROMPT_TEMPLATE, locale);
        String prompt = PromptTemplateLoader.render(template, Map.of(
                "preSchema", preDbDoc,
                "testSchema", testDbDoc));
        return PromptTemplateLoader.withOutputLanguage(prompt, locale);
    }
}
