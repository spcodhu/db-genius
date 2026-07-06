package com.dbgenius.agent;

import com.dbgenius.agent.tool.DbCompareTool;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.model.vo.SseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
public class DbCompareAgent extends ToolCallAgent {

    public DbCompareAgent(ChatClient chatClient, DbCompareTool dbCompareTool,
                          SqlExecuteTool sqlExecuteTool, TerminateTool terminateTool,
                          String preDbDoc, String testDbDoc) {
        super(
                "DbCompareAgent",
                buildSystemPrompt(preDbDoc, testDbDoc),
                "Continue the comparison. If structures have been compared, analyze the differences and generate the deployment SQL. When done, call doTerminate.",
                15,
                chatClient,
                dbCompareTool,
                sqlExecuteTool,
                terminateTool
        );
    }

    @Override
    protected void onStepStart(SseEmitter emitter, String userPrompt) throws Exception {
        super.onStepStart(emitter, userPrompt);
        sendEvent(emitter, SseEvent.of(taskId, 0, "thinking",
                "Starting database comparison analysis. Will compare table structures between pre and test environments."));
    }

    private static String buildSystemPrompt(String preDbDoc, String testDbDoc) {
        return """
                You are DB-Genius, a database version comparison and migration expert. Your job is to compare two database environments and generate deployment SQL scripts.
                
                ## Context
                - **Pre Database** (production mirror): The current production database state.
                - **Test Database**: The test environment with pre-release SQL changes already applied.
                
                ## Your Workflow
                1. Use the compareDatabases tool to get the structural differences.
                2. Analyze the differences carefully:
                   - New tables in test (need CREATE TABLE)
                   - Dropped tables from pre (need DROP TABLE - but warn the user!)
                   - Modified columns (need ALTER TABLE)
                   - New columns in test (need ALTER TABLE ADD COLUMN)
                   - Dropped columns (need ALTER TABLE DROP COLUMN - warn user!)
                3. Generate a well-organized deployment SQL report:
                   - Group changes by type (DDL additions, DDL modifications, DDL deletions)
                   - Include comments explaining each change
                   - Order the SQL statements correctly (dependencies first)
                4. Present the report in a clear, readable format using Markdown tables and code blocks.
                5. Highlight any risky operations (DROP TABLE, DROP COLUMN, data type changes).
                6. When done, call doTerminate with a summary.
                
                ## Rules
                - Always verify the comparison results before generating SQL.
                - For destructive operations, add clear warnings.
                - Respond in the same language as the user.
                - Format the output beautifully with proper sections and headers.
                
                ## Pre Database Schema
                %s
                
                ## Test Database Schema
                %s
                """.formatted(preDbDoc, testDbDoc);
    }
}
