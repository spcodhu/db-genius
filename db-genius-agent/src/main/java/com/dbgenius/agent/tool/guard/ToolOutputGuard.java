package com.dbgenius.agent.tool.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具输出超长拦截器（Tier-0，始终生效）：单条工具结果超过阈值时做<b>结构感知</b>截断，
 * 只影响写回 {@code messageList}（即下一次发给模型的 prompt）的版本，落库与 SSE 展示仍用原文。
 *
 * <p><b>为什么必须结构感知：</b>按字符腰斩 JSON 会切出非法 JSON（如 {@code {"data":[{"id":1,"na}），
 * 模型解析失败后倾向于「重试同一条 SQL」，正是死循环的源头。本类对行集型输出<b>按行裁剪</b>，
 * 保证产出永远是合法 JSON；其余输出统一封装为带 {@code preview} 字段的合法 JSON 信封。</p>
 *
 * <p><b>与模型的契约：</b>截断提示统一以 {@link #TRUNCATED_PREFIX} 开头，语义在各 Agent 系统提示词的
 * 「上下文与超长输出约定」小节中显式约定（见 {@code prompts/_context-policy_*.md}）：这是系统行为、
 * 不是数据错误，不要重复执行同一条语句，而应收窄查询或用 {@code readToolOutput} 分页取回。
 * 提示文本面向 LLM，按项目约定保持英文字面量、不做 i18n。</p>
 */
@Slf4j
@Component
public class ToolOutputGuard {

    /** 工具输出被截断的统一标记前缀，与系统提示词约定一致 */
    public static final String TRUNCATED_PREFIX = "[TRUNCATED:TOOL_OUTPUT_TOO_LONG]";

    /** 行集型输出中可能承载数据数组的字段名（SqlExecuteTool 的 data、MongoDB 的 result、解析器的 rows） */
    private static final List<String> ROW_ARRAY_FIELDS = List.of("data", "result", "rows", "values");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 单条工具输出的字符上限，超过即截断 */
    @Value("${db-genius.context.tool-output.max-chars:4000}")
    private int maxChars = 4000;

    /** 行集裁剪时最多保留的行数（与 max-chars 共同约束，先到先止） */
    @Value("${db-genius.context.tool-output.max-rows:50}")
    private int maxRows = 50;

    /**
     * 按工具名覆盖字符上限，形如 {@code compareDatabases=8000,readFile=6000}；
     * 未配置的工具走 max-chars。用逗号分隔的字符串而非 SpEL Map，避免配置缺省时的启动期解析风险。
     */
    @Value("${db-genius.context.tool-output.per-tool-max-chars:}")
    private String perToolMaxCharsConfig = "";

    private volatile Map<String, Integer> perToolMaxChars;

    private final ToolOutputArtifactStore artifactStore;

    public ToolOutputGuard(ToolOutputArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    /**
     * 供非 Spring 场景（单测、直接 new 出来的 Agent）使用的默认实例，阈值取字段默认值。
     */
    public static ToolOutputGuard withDefaults() {
        return new ToolOutputGuard(new ToolOutputArtifactStore());
    }

    /**
     * 拦截并按需截断一条工具输出。
     *
     * @param taskId   当前任务 ID，用于登记完整原文；为 null 时降级为「无取回句柄」的纯截断
     * @param toolName 工具名
     * @param rawOutput 工具原始输出
     * @return 未超限时原样返回；超限时返回结构感知截断后的合法 JSON
     */
    public String guard(String taskId, String toolName, String rawOutput) {
        int limit = limitFor(toolName);
        if (rawOutput == null || rawOutput.length() <= limit) {
            return rawOutput;
        }

        String artifactId = artifactStore.register(taskId, toolName, rawOutput);
        String truncated = truncateRowSet(rawOutput, toolName, artifactId, limit);
        if (truncated == null) {
            truncated = truncateAsText(rawOutput, toolName, artifactId, limit);
        }
        log.warn("[ToolOutputGuard] truncated oversized output from '{}': {} -> {} chars (artifactId={})",
                toolName, rawOutput.length(), truncated.length(), artifactId);
        return truncated;
    }

    /** 释放任务的制品仓，由 Agent 运行结束时调用。 */
    public void evictTask(String taskId) {
        artifactStore.evictTask(taskId);
    }

    /**
     * 把一段内容登记进制品仓换取取回句柄，不做任何截断。供 Tier-1 观测遮蔽
     * （{@code ObservationElider}）在丢弃早期工具结果前保留可恢复引用。
     *
     * <p>若该内容本身已是本类截断产出的信封（带 {@code artifactId} 字段），直接复用其中的句柄，
     * 避免为同一份数据重复登记两份制品。</p>
     *
     * @return 取回句柄；taskId 为空或内容为空时返回 null
     */
    public String park(String taskId, String toolName, String content) {
        String existing = existingArtifactId(content);
        return existing != null ? existing : artifactStore.register(taskId, toolName, content);
    }

    /** 从截断信封中提取此前登记的 artifactId；不是信封或无该字段时返回 null。 */
    private String existingArtifactId(String content) {
        if (content == null || !content.contains("artifactId")) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            JsonNode artifactId = node.isObject() ? node.get("artifactId") : null;
            return artifactId != null && artifactId.isTextual() ? artifactId.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int limitFor(String toolName) {
        Integer override = toolName == null ? null : perToolOverrides().get(toolName);
        return Math.max(200, override != null ? override : maxChars);
    }

    /** 懒解析 per-tool 覆盖配置；单条格式非法时忽略该条，绝不影响正常截断。 */
    private Map<String, Integer> perToolOverrides() {
        Map<String, Integer> cached = perToolMaxChars;
        if (cached != null) {
            return cached;
        }
        Map<String, Integer> parsed = new LinkedHashMap<>();
        if (perToolMaxCharsConfig != null && !perToolMaxCharsConfig.isBlank()) {
            for (String entry : perToolMaxCharsConfig.split(",")) {
                String[] pair = entry.split("=", 2);
                if (pair.length != 2) {
                    continue;
                }
                try {
                    parsed.put(pair[0].strip(), Integer.parseInt(pair[1].strip()));
                } catch (NumberFormatException e) {
                    log.warn("[ToolOutputGuard] ignored malformed per-tool-max-chars entry: {}", entry);
                }
            }
        }
        perToolMaxChars = parsed;
        return parsed;
    }

    /**
     * 行集型截断：识别 JSON 对象中的数据数组字段，按行累加保留到触及行数/字符上限为止，
     * 其余字段原样保留，并补上 truncated/artifactId/notice 元信息。产出仍是合法 JSON。
     *
     * @return 不适用（非 JSON 对象 / 无数组字段 / 裁剪后仍超限）时返回 null，由调用方回退文本截断
     */
    private String truncateRowSet(String rawOutput, String toolName, String artifactId, int limit) {
        try {
            JsonNode root = objectMapper.readTree(rawOutput);
            if (!root.isObject()) {
                return null;
            }
            String arrayField = ROW_ARRAY_FIELDS.stream()
                    .filter(field -> root.get(field) != null && root.get(field).isArray())
                    .findFirst()
                    .orElse(null);
            if (arrayField == null) {
                return null;
            }

            ArrayNode source = (ArrayNode) root.get(arrayField);
            ObjectNode result = objectMapper.createObjectNode();
            // 先拷贝非数组字段（success/rowCount 等元信息），保持模型熟悉的结构
            root.fields().forEachRemaining(entry -> {
                if (!entry.getKey().equals(arrayField)) {
                    result.set(entry.getKey(), entry.getValue());
                }
            });

            // 预留出元信息与 notice 的空间，避免拼完反而超限
            int budget = Math.max(200, limit - result.toString().length() - 600);
            ArrayNode kept = objectMapper.createArrayNode();
            int used = 0;
            for (JsonNode row : source) {
                if (kept.size() >= maxRows) {
                    break;
                }
                int rowLength = row.toString().length() + 1;
                if (kept.size() > 0 && used + rowLength > budget) {
                    break;
                }
                kept.add(row);
                used += rowLength;
            }
            if (kept.size() >= source.size()) {
                // 数组本身不是膨胀主因（元信息/其他字段过大），交给文本截断处理
                return null;
            }

            result.put("truncated", true);
            result.put("truncatedReason", "TOOL_OUTPUT_TOO_LONG");
            result.put("returnedItems", kept.size());
            result.put("totalItems", source.size());
            if (artifactId != null) {
                result.put("artifactId", artifactId);
            }
            result.put("notice", rowSetNotice(toolName, artifactId, kept.size(), source.size(), rawOutput.length()));
            result.set(arrayField, kept);

            String json = result.toString();
            return json.length() > limit * 2 ? null : json;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通用文本截断：头部 2/3 + 尾部 1/3 预览，封装进合法 JSON 信封，
     * 避免把腰斩后的非法 JSON 直接丢给模型。
     */
    private String truncateAsText(String rawOutput, String toolName, String artifactId, int limit) {
        int previewBudget = Math.max(200, limit - 600);
        int headLength = previewBudget * 2 / 3;
        int tailLength = Math.max(0, previewBudget - headLength);
        String head = rawOutput.substring(0, Math.min(headLength, rawOutput.length()));
        String tail = tailLength > 0 && rawOutput.length() > headLength + tailLength
                ? rawOutput.substring(rawOutput.length() - tailLength) : "";
        int omitted = rawOutput.length() - head.length() - tail.length();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("truncated", true);
        envelope.put("truncatedReason", "TOOL_OUTPUT_TOO_LONG");
        envelope.put("originalChars", rawOutput.length());
        envelope.put("omittedChars", omitted);
        if (artifactId != null) {
            envelope.put("artifactId", artifactId);
        }
        envelope.put("notice", textNotice(toolName, artifactId, omitted, rawOutput.length()));
        envelope.put("preview", head + (tail.isEmpty() ? "" : "\n...\n" + tail));
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return TRUNCATED_PREFIX + " tool " + toolName + " output was too long and could not be encoded.";
        }
    }

    private String rowSetNotice(String toolName, String artifactId, int kept, int total, int originalChars) {
        return TRUNCATED_PREFIX + " Tool '" + toolName + "' returned " + total + " item(s) / "
                + originalChars + " chars; only the first " + kept + " item(s) are shown. "
                + "This is a system-side truncation, NOT a query failure - do NOT re-run the same statement. "
                + recoveryHint(artifactId);
    }

    private String textNotice(String toolName, String artifactId, int omitted, int originalChars) {
        return TRUNCATED_PREFIX + " Tool '" + toolName + "' output was " + originalChars
                + " chars; " + omitted + " chars were omitted from the middle. "
                + "This is a system-side truncation, NOT a tool failure - do NOT re-run the same call. "
                + recoveryHint(artifactId);
    }

    private String recoveryHint(String artifactId) {
        String narrow = "Prefer narrowing the request instead: add WHERE/LIMIT, select fewer columns, "
                + "or use aggregates (COUNT/SUM/GROUP BY).";
        if (artifactId == null) {
            return narrow;
        }
        return narrow + " If you truly need the omitted part, call readToolOutput(artifactId=\"" + artifactId
                + "\", offset=0, limit=2000) to page through the full output.";
    }
}
