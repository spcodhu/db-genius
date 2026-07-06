package com.dbgenius.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TerminateTool {

    @Tool(description = "Call this tool when the task is fully completed and no further steps are needed. This will terminate the agent workflow.")
    public String doTerminate(@ToolParam(description = "A brief summary of what was accomplished") String summary) {
        return "Task terminated. Summary: " + summary;
    }
}
