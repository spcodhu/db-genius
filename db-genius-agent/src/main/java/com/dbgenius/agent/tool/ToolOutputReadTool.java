package com.dbgenius.agent.tool;

import com.dbgenius.agent.tool.guard.ToolOutputArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 被截断工具输出的分页取回工具：配合 {@link com.dbgenius.agent.tool.guard.ToolOutputGuard} 的
 * {@code [TRUNCATED:TOOL_OUTPUT_TOO_LONG]} 提示使用，让模型能在真正需要时拿回被省略的片段，
 * 而不是靠重复执行同一条语句去「碰运气」——这是防止截断诱发死循环的关键通道。
 *
 * <p>taskId 经 {@link ToolContext} 传递（LLM 不可见），保证只能读到本任务登记的制品。
 * 所有失败均返回结构化错误文本，不抛异常打断 ReAct 循环。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolOutputReadTool {

    private static final int DEFAULT_LIMIT = 2000;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolOutputArtifactStore artifactStore;

    @Tool(description = "Retrieve a slice of a previously truncated tool output using the artifactId shown in a "
            + "[TRUNCATED:TOOL_OUTPUT_TOO_LONG] notice. Use this only when you genuinely need the omitted part; "
            + "narrowing the original query (WHERE/LIMIT/fewer columns/aggregates) is usually better. "
            + "Returns {content, offset, nextOffset, total, hasMore} - pass nextOffset to continue paging.")
    public String readToolOutput(
            @ToolParam(description = "Artifact handle from a [TRUNCATED] notice, e.g. to_1a") String artifactId,
            @ToolParam(description = "0-based character offset to start from, e.g. 0") Integer offset,
            @ToolParam(description = "Max characters to return (system caps it), e.g. 2000") Integer limit,
            ToolContext toolContext) {
        try {
            Map<String, Object> context = toolContext != null ? toolContext.getContext() : null;
            Object taskIdObj = context != null ? context.get(ToolOutputArtifactStore.CONTEXT_TASK_ID) : null;
            if (!(taskIdObj instanceof String taskId) || taskId.isBlank()) {
                return errorJson("Tool output retrieval is unavailable: missing task context.");
            }
            if (artifactId == null || artifactId.isBlank()) {
                return errorJson("artifactId is required. Copy it from the [TRUNCATED:TOOL_OUTPUT_TOO_LONG] notice.");
            }

            int from = offset == null ? 0 : Math.max(0, offset);
            int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
            ToolOutputArtifactStore.Slice slice = artifactStore.read(taskId, artifactId, from, size);
            if (slice == null) {
                return errorJson("No stored output found for artifactId=" + artifactId
                        + ". It may have expired or belong to an earlier turn. "
                        + "Re-run the original call with a narrower scope instead of retrying this one.");
            }

            log.info("readToolOutput artifactId={} offset={} -> {}/{}",
                    artifactId, from, slice.nextOffset(), slice.total());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("artifactId", artifactId);
            result.put("toolName", slice.toolName());
            result.put("offset", slice.offset());
            result.put("nextOffset", slice.nextOffset());
            result.put("total", slice.total());
            result.put("hasMore", slice.hasMore());
            result.put("content", slice.content());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("readToolOutput 失败 artifactId={}", artifactId, e);
            return errorJson("Failed to read stored tool output: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "Error: " + message;
        }
    }
}
