package com.dbgenius.agent;

import com.dbgenius.agent.prompt.PromptTemplateLoader;
import com.dbgenius.agent.tool.FileReadTool;
import com.dbgenius.agent.tool.ImageReadTool;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.agent.tool.ToolOutputReadTool;
import com.dbgenius.model.vo.SseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Locale;
import java.util.Map;

@Slf4j
public class DbWorkflowAgent extends ToolCallAgent {

    private static final String PROMPT_TEMPLATE = "db-workflow-agent-system";
    private static final String FILE_SECTION_TEMPLATE = "db-workflow-agent-files";

    private final String dbDocContext;
    private final boolean hasFiles;

    public DbWorkflowAgent(ReasoningChatModel reasoningChatModel,
                           SqlExecuteTool sqlExecuteTool,
                           FileReadTool fileReadTool, ImageReadTool imageReadTool,
                           TerminateTool terminateTool,
                           ToolOutputReadTool toolOutputReadTool,
                           String dbDocContext, boolean hasFiles,
                           Map<String, Object> toolContext, Locale locale) {
        super(
                "DbWorkflowAgent",
                buildSystemPrompt(dbDocContext, hasFiles, locale),
                buildNextStepPrompt(hasFiles),
                20,
                reasoningChatModel,
                toolContext,
                sqlExecuteTool,
                fileReadTool,
                imageReadTool,
                terminateTool,
                toolOutputReadTool
        );
        this.dbDocContext = dbDocContext;
        this.hasFiles = hasFiles;
        setLocale(locale);
    }

    @Override
    protected void onStepStart(SseEmitter emitter, String userPrompt) throws Exception {
        super.onStepStart(emitter, userPrompt);
        String hint = hasFiles
                ? "Workflow mode with file upload detected. Will parse file first, then process data."
                : "Complex workflow mode. Planning multi-step execution.";
        sendEvent(emitter, SseEvent.of(taskId, 0, "thinking", hint));
    }

    private static String buildSystemPrompt(String dbDoc, boolean hasFiles, Locale locale) {
        String template = PromptTemplateLoader.load(PROMPT_TEMPLATE, locale);
        String fileSection = hasFiles ? PromptTemplateLoader.load(FILE_SECTION_TEMPLATE, locale) : "";
        String prompt = PromptTemplateLoader.render(template, Map.of(
                "fileSection", fileSection,
                "schema", dbDoc));
        return PromptTemplateLoader.withOutputLanguage(
                PromptTemplateLoader.withContextPolicy(prompt, locale), locale);
    }

    private static String buildNextStepPrompt(boolean hasFiles) {
        if (hasFiles) {
            return "Continue with the workflow. If the file has been parsed, proceed to process the data. If data has been inserted, verify it. When everything is done and verified, call doTerminate.";
        }
        return "Continue with the current task. If a step is done, proceed to the next. When everything is complete and verified, call doTerminate.";
    }
}
