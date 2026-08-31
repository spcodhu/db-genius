package com.dbgenius.agent;

import com.dbgenius.agent.prompt.PromptTemplateLoader;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.agent.tool.ToolOutputReadTool;
import com.dbgenius.model.vo.SseEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Map;

@Slf4j
public class DbSqlAgent extends ToolCallAgent {

    private static final String PROMPT_TEMPLATE = "db-sql-agent-system";

    private final String dbDocContext;

    public DbSqlAgent(ReasoningChatModel reasoningChatModel,
                      SqlExecuteTool sqlExecuteTool, TerminateTool terminateTool,
                      ToolOutputReadTool toolOutputReadTool,
                      String dbDocContext, String dialectContext,
                      Map<String, Object> toolContext, Locale locale) {
        super(
                "DbSqlAgent",
                buildSystemPrompt(dbDocContext, dialectContext, locale),
                "Based on the user's request, analyze the intent, generate the appropriate SQL, execute it, and report the results. When done, call doTerminate.",
                10,
                reasoningChatModel,
                toolContext,
                sqlExecuteTool,
                terminateTool,
                toolOutputReadTool
        );
        this.dbDocContext = dbDocContext;
        setLocale(locale);
    }

    @Override
    protected void onStepStart(String userPrompt) throws Exception {
        super.onStepStart(userPrompt);
        sendEvent(SseEvent.of(taskId, 0, "thinking",
                "Analyzing your query against the database. Database schema loaded."));
    }

    private static String buildSystemPrompt(String dbDoc, String dialectContext, Locale locale) {
        String template = PromptTemplateLoader.load(PROMPT_TEMPLATE, locale);
        String prompt = PromptTemplateLoader.render(template, Map.of(
                "dialect", dialectContext,
                "schema", dbDoc));
        return PromptTemplateLoader.withOutputLanguage(
                PromptTemplateLoader.withContextPolicy(prompt, locale), locale);
    }
}
