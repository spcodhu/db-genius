package com.dbgenius.agent.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 Agent 运行内的死循环护栏：<b>每个 Agent 实例持有一个，状态不跨轮复用</b>。
 *
 * <p><b>解决什么问题：</b>工具输出被截断 / 语句超时 / 结果不符合预期时，模型的典型失败模式是
 * 用完全相同的参数一遍遍重试，直到耗尽 maxSteps。只靠 maxSteps 兜底意味着用户要为一整轮
 * 无效的模型调用付费并等待。本类做三层防护：</p>
 * <ol>
 *   <li><b>重复调用拦截</b>：同一 (工具名 + 规范化参数) 第 N 次出现时不再真正执行，返回
 *       可行动的改变策略指引；</li>
 *   <li><b>截断累计提示</b>：本轮被截断次数达标时注入一次系统指引，引导改用聚合 / 分页；</li>
 *   <li><b>硬性熔断</b>：重复或截断次数超过上限时要求模型立即 doTerminate，由调用方把
 *       maxSteps 收敛到当前步 + 1，<b>仍走正常的 summary 流程优雅收尾</b>，而不是抛错。</li>
 * </ol>
 *
 * <p>反馈文本面向 LLM，按项目约定保持英文字面量、不做 i18n。</p>
 */
@Slf4j
public class LoopBreaker {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 签名规范化专用 mapper：按 key 排序序列化，使 {@code {"a":1,"b":2}} 与
     * {@code {"b":2,"a":1}} 得到同一签名——模型重试时常常只是重排了参数顺序。
     */
    private static final ObjectMapper canonicalMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** 同一签名达到该次数即拦截（第 1、2 次放行，第 3 次拦截） */
    private final int repeatThreshold;

    /** 同一签名达到该次数即硬熔断，要求立即收尾 */
    private final int hardStopRepeat;

    /** 本轮累计截断次数达到该值即注入一次改用聚合/分页的指引 */
    private final int truncationHintThreshold;

    private final Map<String, Integer> callCounts = new HashMap<>();
    private int truncationCount;
    private boolean truncationHintEmitted;
    private boolean hardStopTriggered;

    public LoopBreaker(int repeatThreshold, int hardStopRepeat, int truncationHintThreshold) {
        this.repeatThreshold = Math.max(2, repeatThreshold);
        this.hardStopRepeat = Math.max(this.repeatThreshold, hardStopRepeat);
        this.truncationHintThreshold = Math.max(1, truncationHintThreshold);
    }

    /**
     * 在真正执行工具前记账并判定是否需要拦截。
     *
     * @return 需要拦截时返回可直接回给模型的反馈文本（按 toolCallId 一一对应）；放行返回空列表
     */
    public List<String> inspect(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<String> feedback = new ArrayList<>(toolCalls.size());
        boolean blocked = false;
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            int count = callCounts.merge(signature(toolCall), 1, Integer::sum);
            if (count >= hardStopRepeat) {
                hardStopTriggered = true;
            }
            if (count >= repeatThreshold) {
                blocked = true;
                feedback.add(repeatedCallFeedback(toolCall.name(), count));
            } else {
                feedback.add(null);
            }
        }
        if (!blocked) {
            return List.of();
        }
        log.warn("[LoopBreaker] blocked repeated tool call(s): {}", feedback.stream().filter(f -> f != null).count());
        // 未触发重复的调用一并拦截：一步内多工具时不能只执行一半，否则 tool_call/tool_response 配对会断
        return feedback.stream().map(f -> f != null ? f : companionBlockedFeedback()).toList();
    }

    /** 记录一次工具输出被截断，返回是否需要注入「改用聚合/分页」的一次性指引。 */
    public boolean recordTruncationAndNeedsHint() {
        truncationCount++;
        if (truncationHintEmitted || truncationCount < truncationHintThreshold) {
            return false;
        }
        truncationHintEmitted = true;
        log.warn("[LoopBreaker] {} truncated tool output(s) in this run, injecting narrowing hint", truncationCount);
        return true;
    }

    /** 是否已触发硬熔断：调用方据此把 maxSteps 收敛，保证一定收敛到 summary。 */
    public boolean isHardStopTriggered() {
        return hardStopTriggered;
    }

    public String narrowingHint() {
        return "System notice: several tool outputs in this run were truncated because they were too large. "
                + "Stop re-running broad statements. Narrow the scope instead: add WHERE/LIMIT, select only the "
                + "columns you need, or answer with aggregates (COUNT/SUM/GROUP BY). If you already have enough "
                + "information, report your conclusion and call doTerminate.";
    }

    public String hardStopHint() {
        return "System notice: the repeated-call guard has been triggered. Do NOT call any tool with the same "
                + "arguments again. Immediately report your conclusion based on the information gathered so far, "
                + "state explicitly what could not be determined and why, then call doTerminate.";
    }

    /**
     * 调用签名：工具名 + 规范化后的参数。参数按 JSON 解析后重新序列化，
     * 使 {@code {"a":1,"b":2}} 与 {@code {"b":2, "a":1}} 视为同一次调用；解析失败退回原始字符串。
     */
    private String signature(AssistantMessage.ToolCall toolCall) {
        String arguments = toolCall.arguments() == null ? "" : toolCall.arguments();
        try {
            JsonNode node = canonicalMapper.readTree(arguments);
            arguments = canonicalMapper.writeValueAsString(canonicalMapper.treeToValue(node, Object.class));
        } catch (Exception e) {
            arguments = arguments.strip();
        }
        return toolCall.name() + "#" + arguments;
    }

    private String repeatedCallFeedback(String toolName, int count) {
        return errorJson("REPEATED_CALL", "You have already called '" + toolName + "' with identical arguments "
                + count + " times and it was blocked by the system. Do NOT repeat it. Change strategy: narrow the "
                + "query (WHERE/LIMIT/fewer columns/aggregates), use readToolOutput to page through a previously "
                + "truncated result, or call doTerminate and report your conclusion together with its limitations.");
    }

    private String companionBlockedFeedback() {
        return errorJson("BLOCKED_WITH_BATCH", "This call was skipped because another tool call in the same step "
                + "was blocked by the repeated-call guard. Re-issue it on its own with a different strategy if it "
                + "is still needed.");
    }

    private String errorJson(String code, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("success", false, "error", code, "message", message));
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + code + "\"}";
        }
    }
}
