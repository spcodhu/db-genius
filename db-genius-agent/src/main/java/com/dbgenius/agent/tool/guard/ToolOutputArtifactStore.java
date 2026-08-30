package com.dbgenius.agent.tool.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具输出制品仓：{@link ToolOutputGuard} 截断超长工具输出前，把<b>完整原文</b>存入本仓并换取一个短句柄
 * （artifactId），句柄写进给模型看的截断提示中，模型可用 {@code readToolOutput} 分页取回。
 *
 * <p><b>为什么必须有这个通道：</b>纯截断会让模型陷入「看不到数据 → 重试同一条语句 → 又被截断」的死循环。
 * 提供可恢复的取回句柄后，「需要被省略的内容」从无限重试变成一次确定性的分页读取，
 * 且上下文只增长模型真正需要的那一段（参考 Manus 的「可恢复压缩」思路）。</p>
 *
 * <p><b>生命周期与隔离：</b>制品按 taskId 分仓，随 Agent 运行结束（{@code cleanup()}）整仓释放；
 * 仅内存保存，不落库、不进 OSS，跨轮次不保证可用。taskId 经 Spring AI {@code ToolContext} 传递，
 * <b>对 LLM 不可见</b>，与 {@code FileAccessGuard} 的 userId/allowedFileIds 同款做法，天然做到任务间隔离。</p>
 */
@Slf4j
@Component
public class ToolOutputArtifactStore {

    /** ToolContext key：当前任务 ID（String），用于制品仓寻址与隔离 */
    public static final String CONTEXT_TASK_ID = "taskId";

    /** 单次取回的字符上限，防止模型用一次 readToolOutput 把整个原文塞回上下文 */
    private static final int READ_SLICE_HARD_CAP = 8000;

    /** 制品存活时间（分钟）：兜底清理，正常路径由 Agent cleanup 主动释放 */
    @Value("${db-genius.context.tool-output.artifact-ttl-minutes:30}")
    private int ttlMinutes = 30;

    /** 单个任务最多保留的制品数，超出按插入顺序淘汰最旧的 */
    @Value("${db-genius.context.tool-output.artifact-max-per-task:20}")
    private int maxPerTask = 20;

    private final Map<String, TaskArtifacts> byTask = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 登记一份完整的工具输出原文。
     *
     * @param taskId   当前任务 ID，为空时不登记（返回 null，调用方降级为「无取回句柄」的纯截断）
     * @param toolName 工具名，仅用于日志与取回时的元信息
     * @param content  完整原文
     * @return 短句柄 artifactId；未登记返回 null
     */
    public String register(String taskId, String toolName, String content) {
        if (taskId == null || taskId.isBlank() || content == null || content.isEmpty()) {
            return null;
        }
        purgeExpired();
        TaskArtifacts artifacts = byTask.computeIfAbsent(taskId, key -> new TaskArtifacts());
        String artifactId = "to_" + Long.toHexString(sequence.incrementAndGet());
        artifacts.put(artifactId, new Artifact(toolName, content), maxPerTask);
        log.debug("[ToolOutputArtifactStore] registered {} ({} chars) for task {}",
                artifactId, content.length(), taskId);
        return artifactId;
    }

    /**
     * 按句柄取回一段原文。
     *
     * @return 命中返回切片结果，未命中（句柄不存在 / 已释放 / 不属于该任务）返回 null
     */
    public Slice read(String taskId, String artifactId, int offset, int limit) {
        if (taskId == null || artifactId == null) {
            return null;
        }
        TaskArtifacts artifacts = byTask.get(taskId);
        Artifact artifact = artifacts != null ? artifacts.get(artifactId) : null;
        if (artifact == null) {
            return null;
        }
        String content = artifact.content();
        int total = content.length();
        int from = Math.max(0, Math.min(offset, total));
        int size = Math.max(1, Math.min(limit, READ_SLICE_HARD_CAP));
        int to = Math.min(total, from + size);
        return new Slice(artifact.toolName(), content.substring(from, to), from, to, total);
    }

    /** 释放某个任务的全部制品：由 Agent 运行结束时调用，避免长会话堆积内存。 */
    public void evictTask(String taskId) {
        if (taskId == null) {
            return;
        }
        TaskArtifacts removed = byTask.remove(taskId);
        if (removed != null) {
            log.debug("[ToolOutputArtifactStore] evicted {} artifact(s) for task {}", removed.size(), taskId);
        }
    }

    /** TTL 兜底清理：任务异常中断（如 SSE 断开且 cleanup 未跑到）时防止内存泄漏。 */
    private void purgeExpired() {
        long deadline = System.currentTimeMillis() - ttlMinutes * 60_000L;
        byTask.entrySet().removeIf(entry -> entry.getValue().lastTouchedAt() < deadline);
    }

    /** 取回结果：{@code nextOffset < total} 表示后面还有内容。 */
    public record Slice(String toolName, String content, int offset, int nextOffset, int total) {

        public boolean hasMore() {
            return nextOffset < total;
        }
    }

    private record Artifact(String toolName, String content) {
    }

    /** 单个任务的制品集合：按插入顺序保留，超出上限淘汰最旧的一条。 */
    private static final class TaskArtifacts {

        private final LinkedHashMap<String, Artifact> artifacts = new LinkedHashMap<>();
        private volatile long lastTouchedAt = System.currentTimeMillis();

        synchronized void put(String artifactId, Artifact artifact, int maxSize) {
            artifacts.put(artifactId, artifact);
            Iterator<String> iterator = artifacts.keySet().iterator();
            while (artifacts.size() > Math.max(1, maxSize) && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
            lastTouchedAt = System.currentTimeMillis();
        }

        synchronized Artifact get(String artifactId) {
            lastTouchedAt = System.currentTimeMillis();
            return artifacts.get(artifactId);
        }

        synchronized int size() {
            return artifacts.size();
        }

        long lastTouchedAt() {
            return lastTouchedAt;
        }
    }
}
