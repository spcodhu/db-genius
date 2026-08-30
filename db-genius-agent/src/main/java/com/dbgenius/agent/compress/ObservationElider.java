package com.dbgenius.agent.compress;

import com.dbgenius.agent.tool.guard.ToolOutputGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮内上下文瘦身的 Tier-1 策略：<b>确定性观测遮蔽</b>（Observation Masking），零 LLM 调用、零额外时延。
 *
 * <p><b>为什么优先于 LLM 摘要：</b>单轮上下文膨胀的主因是每步工具结果的线性累加，而这些早期观测的
 * 结论通常已经写进模型自己的分析正文里。直接把较早步骤的工具结果替换成占位符，即可把工具结果的占用
 * 从 {@code O(步数 × 结果大小)} 压到 {@code O(keep-last-steps × 单条上限)}，而 LLM 摘要
 * （{@link StepHistoryCondenser}）要额外付一次全量 prompt 的费用与数秒到数十秒的等待。
 * 因此本策略作为默认主力，摘要降级为极端场景兜底。</p>
 *
 * <p><b>协议安全性：</b>只改写 {@link ToolResponseMessage.ToolResponse#responseData()} 文本，
 * <b>不增删任何消息</b>，assistant 的 {@code tool_calls} 与 tool_response 的配对与 id 对应关系
 * 100% 保持——这比「把多条消息整体替换为一条摘要」在 OpenAI 兼容协议下安全一个量级。</p>
 *
 * <p>被遮蔽的原文仍可经 {@code readToolOutput(artifactId)} 取回：遮蔽时会把原文登记进制品仓，
 * 占位符中带上句柄，模型不会因此陷入「数据没了 → 重试」的循环。</p>
 */
@Slf4j
@Component
public class ObservationElider {

    /** 早期观测被遮蔽的统一标记前缀，与系统提示词（_context-policy）约定一致 */
    public static final String ELIDED_PREFIX = "[ELIDED:STALE_OBSERVATION]";

    /** 短于此长度的工具结果不值得遮蔽（占位符本身也要占字符），直接跳过 */
    private static final int MIN_ELIDABLE_CHARS = 200;

    @Value("${db-genius.context.in-run.elide.enabled:true}")
    private boolean enabled = true;

    /** 触发阈值：估算占用达到 contextWindow 的该比例即开始遮蔽 */
    @Value("${db-genius.context.in-run.elide.threshold:0.6}")
    private double threshold = 0.6;

    /** 始终保留最近 N 个步骤的工具结果原样不动 */
    @Value("${db-genius.context.in-run.elide.keep-last-steps:3}")
    private int keepLastSteps = 3;

    private final ToolOutputGuard toolOutputGuard;

    public ObservationElider(ToolOutputGuard toolOutputGuard) {
        this.toolOutputGuard = toolOutputGuard;
    }

    /**
     * 若估算占用超过阈值，遮蔽 {@code messageList[turnStartIndex..]} 中除最近
     * {@link #keepLastSteps} 个 tool 响应之外的更早工具结果。
     *
     * @param messageList    当前运行时消息列表（原地修改）
     * @param turnStartIndex 本轮 Agent 生成内容的起始下标，此下标之前的历史消息永不触碰
     * @param systemPrompt   系统提示词（计入 token 估算）
     * @param contextWindow  当前模型上下文窗口，未知（null 或 &lt;=0）时跳过
     * @param taskId         当前任务 ID，用于登记原文换取可取回的 artifactId；可为 null
     * @return 本次遮蔽的结果；未执行时 {@link Result#elided()} 为 false
     */
    public Result elideIfNeeded(List<Message> messageList, int turnStartIndex, String systemPrompt,
                                Integer contextWindow, String taskId) {
        if (!enabled || contextWindow == null || contextWindow <= 0) {
            return Result.skipped();
        }
        if (turnStartIndex < 0 || turnStartIndex > messageList.size()) {
            log.warn("[ObservationElider] invalid turnStartIndex {} for messageList size {}",
                    turnStartIndex, messageList.size());
            return Result.skipped();
        }

        int before = TokenEstimator.estimate(systemPrompt) + TokenEstimator.estimate(messageList);
        if (before < contextWindow * threshold) {
            return Result.skipped();
        }

        // 倒序扫描：跳过最近 keepLastSteps 个 tool 响应，再往前的全部遮蔽
        List<Integer> targets = new ArrayList<>();
        int seen = 0;
        for (int i = messageList.size() - 1; i >= turnStartIndex; i--) {
            if (!(messageList.get(i) instanceof ToolResponseMessage)) {
                continue;
            }
            seen++;
            if (seen > keepLastSteps) {
                targets.add(i);
            }
        }
        if (targets.isEmpty()) {
            return Result.skipped();
        }

        int elidedCount = 0;
        for (int index : targets) {
            ToolResponseMessage original = (ToolResponseMessage) messageList.get(index);
            ToolResponseMessage elided = elide(original, taskId);
            if (elided != original) {
                messageList.set(index, elided);
                elidedCount++;
            }
        }
        if (elidedCount == 0) {
            return Result.skipped();
        }

        int after = TokenEstimator.estimate(systemPrompt) + TokenEstimator.estimate(messageList);
        log.info("[ObservationElider] elided {} stale observation(s), estimated tokens {} -> {}",
                elidedCount, before, after);
        return new Result(true, elidedCount, before, after);
    }

    /** 逐条替换工具结果文本；已遮蔽或过短的条目原样保留，保证幂等。 */
    private ToolResponseMessage elide(ToolResponseMessage original, String taskId) {
        boolean changed = false;
        List<ToolResponseMessage.ToolResponse> replaced = new ArrayList<>(original.getResponses().size());
        for (ToolResponseMessage.ToolResponse response : original.getResponses()) {
            String data = response.responseData();
            if (data == null || data.length() < MIN_ELIDABLE_CHARS || data.startsWith(ELIDED_PREFIX)) {
                replaced.add(response);
                continue;
            }
            changed = true;
            replaced.add(new ToolResponseMessage.ToolResponse(
                    response.id(), response.name(), placeholder(response.name(), data, taskId)));
        }
        return changed ? ToolResponseMessage.builder().responses(replaced).build() : original;
    }

    /** 占位符文本面向 LLM，按项目约定保持英文字面量、不做 i18n。 */
    private String placeholder(String toolName, String data, String taskId) {
        String artifactId = toolOutputGuard.park(taskId, toolName, data);
        String text = ELIDED_PREFIX + " An earlier result of tool '" + toolName + "' (" + data.length()
                + " chars) was removed from the context to save space. "
                + "Your own earlier analysis already summarises what mattered.";
        if (artifactId != null) {
            text += " If you still need it, call readToolOutput(artifactId=\"" + artifactId
                    + "\", offset=0, limit=2000).";
        }
        return text;
    }

    /** 遮蔽结果快照，供上层发 SSE 事件与日志使用。 */
    public record Result(boolean elided, int elidedCount, int beforeTokens, int afterTokens) {

        static Result skipped() {
            return new Result(false, 0, 0, 0);
        }
    }
}
