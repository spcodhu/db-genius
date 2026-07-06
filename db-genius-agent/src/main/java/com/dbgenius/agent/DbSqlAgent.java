package com.dbgenius.agent;

import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.model.vo.SseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
public class DbSqlAgent extends ToolCallAgent {

    private final String dbDocContext;

    public DbSqlAgent(ChatClient chatClient, SqlExecuteTool sqlExecuteTool,
                      TerminateTool terminateTool, String dbDocContext) {
        super(
                "DbSqlAgent",
                buildSystemPrompt(dbDocContext),
                "Based on the user's request, analyze the intent, generate the appropriate SQL, execute it, and report the results. When done, call doTerminate.",
                10,
                chatClient,
                sqlExecuteTool,
                terminateTool
        );
        this.dbDocContext = dbDocContext;
    }

    @Override
    protected void onStepStart(SseEmitter emitter, String userPrompt) throws Exception {
        super.onStepStart(emitter, userPrompt);
        sendEvent(emitter, SseEvent.of(taskId, 0, "thinking",
                "Analyzing your query against the database. Database schema loaded."));
    }

    private static String buildSystemPrompt(String dbDoc) {
        return """
                You are DB-Genius, an expert database assistant. Your job is to help users query and manage their databases using natural language.
                
                ## Rules
                1. Analyze the user's request carefully and determine the appropriate SQL statement.
                2. Generate safe, correct SQL. For SELECT queries, always add LIMIT 100 unless the user specifies otherwise.
                3. Execute the SQL using the executeSql tool with the correct database config ID.
                4. Report results clearly. If there's an error, explain it and suggest fixes.
                5. When the task is complete, call doTerminate with a brief summary.
                6. NEVER modify production data without explicit user confirmation.
                7. Respond in the same language as the user.
                
                ## Database Schema
                %s
                """.formatted(dbDoc);
    }
}
