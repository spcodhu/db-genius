package com.dbgenius.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DSML 工具调用文本的反解析与清理工具。
 *
 * <p><b>背景：</b>DeepSeek（thinking 模式 + 流式）偶发把工具调用"降级"为纯文本：
 * 结构化 tool_calls 的 arguments 为空甚至缺失，真实参数被模型写进 content 的 DSML 标记里，形如</p>
 * <pre>
 * &lt;｜｜DSML｜｜tool_calls&gt;
 *   &lt;｜｜DSML｜｜invoke name="executeSql"&gt;
 *     &lt;｜｜DSML｜｜parameter name="dbConfigId" string="false"&gt;1&lt;/｜｜DSML｜｜parameter&gt;
 *     &lt;｜｜DSML｜｜parameter name="sql" string="true"&gt;SHOW TABLES&lt;/｜｜DSML｜｜parameter&gt;
 *   &lt;/｜｜DSML｜｜invoke&gt;
 * &lt;/｜｜DSML｜｜tool_calls&gt;
 * </pre>
 *
 * <p>本类提供两个方向的修复：
 * ① {@link #parse} 把 DSML invoke 块反解析回 (name, argumentsJson)，
 * 供 {@link ToolCallAgent#think} 恢复真实的工具调用参数（治本）；
 * ② {@link #strip} 剥离各类 DSML 残留，作为总结输出前的最后安全网（治标兜底）。
 * 正则中统一使用 {@code [｜|]} 兼容全角/半角竖线变体。</p>
 */
@Slf4j
public final class DsmlToolCallParser {

    /** invoke 块：兼容 DSML 前缀与简化 &lt;invoke&gt; 两种写法 */
    private static final Pattern INVOKE_BLOCK = Pattern.compile(
            "<(?:[｜|]*DSML[｜|]*)?invoke\\b([^>]*)>(.*?)</(?:[｜|]*DSML[｜|]*)?invoke\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** parameter 块：同上兼容两种写法 */
    private static final Pattern PARAMETER_BLOCK = Pattern.compile(
            "<(?:[｜|]*DSML[｜|]*)?parameter\\b([^>]*)>(.*?)</(?:[｜|]*DSML[｜|]*)?parameter\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern ATTR_NAME = Pattern.compile("name\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ATTR_STRING = Pattern.compile("string\\s*=\\s*\"(true|false)\"", Pattern.CASE_INSENSITIVE);

    /** 判断 content 中是否疑似存在 DSML 标记（含不完整的起始片段） */
    private static final Pattern DSML_DETECTOR = Pattern.compile(
            "<[｜|]*DSML[｜|]*(tool_calls|invoke)|<(tool_calls|invoke)>",
            Pattern.CASE_INSENSITIVE);

    // ---- strip 用的清理正则（自外层向内层逐层剥离） ----
    private static final Pattern DSML_BLOCK = Pattern.compile(
            "<[｜|]+DSML[｜|]+[^>]*>.*?</[｜|]+DSML[｜|]+[^>]*>", Pattern.DOTALL);
    private static final Pattern DSML_CLOSING_TAG = Pattern.compile("</[｜|]+DSML[｜|]+[^>]*>");
    private static final Pattern SIMPLE_BLOCK = Pattern.compile(
            "<(tool_calls|invoke)\\b[^>]*>.*?</\\1\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DSML_RESIDUAL = Pattern.compile("<?[^<\\n]*[｜|]+DSML[｜|]+[^>]*>?");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DsmlToolCallParser() {
    }

    /** 反解析结果：工具名 + 组装好的 arguments JSON 字符串 */
    public record RecoveredToolCall(String name, String argumentsJson) {
    }

    /** content 中是否疑似包含 DSML 工具调用标记 */
    public static boolean containsDsml(String content) {
        return content != null && DSML_DETECTOR.matcher(content).find();
    }

    /**
     * 解析 content 中的所有 DSML invoke 块。
     *
     * <p>参数类型按 DSML 的 {@code string} 属性还原：
     * {@code string="true"} 恒为字符串；{@code string="false"} 依次尝试 Long → Double → Boolean，
     * 均失败则回退字符串。参数顺序以 LinkedHashMap 保持原文顺序。</p>
     */
    public static List<RecoveredToolCall> parse(String content) {
        List<RecoveredToolCall> result = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return result;
        }
        Matcher invokeMatcher = INVOKE_BLOCK.matcher(content);
        while (invokeMatcher.find()) {
            String attrs = invokeMatcher.group(1);
            String body = invokeMatcher.group(2);

            Matcher nameMatcher = ATTR_NAME.matcher(attrs);
            if (!nameMatcher.find()) {
                continue;
            }
            String name = nameMatcher.group(1);

            Map<String, Object> arguments = new LinkedHashMap<>();
            Matcher paramMatcher = PARAMETER_BLOCK.matcher(body);
            while (paramMatcher.find()) {
                String paramAttrs = paramMatcher.group(1);
                String value = paramMatcher.group(2);

                Matcher pNameMatcher = ATTR_NAME.matcher(paramAttrs);
                if (!pNameMatcher.find()) {
                    continue;
                }
                String paramName = pNameMatcher.group(1);
                arguments.put(paramName, typedValue(paramAttrs, value));
            }

            try {
                result.add(new RecoveredToolCall(name, OBJECT_MAPPER.writeValueAsString(arguments)));
            } catch (Exception e) {
                log.warn("Failed to serialize recovered DSML tool call arguments for {}: {}", name, e.getMessage());
            }
        }
        return result;
    }

    /** 按 string 属性决定参数 JSON 类型；无 string 属性时按 false 语义尽力转数值/布尔 */
    private static Object typedValue(String attrs, String rawValue) {
        String value = rawValue.trim();
        Matcher stringMatcher = ATTR_STRING.matcher(attrs);
        if (stringMatcher.find() && "true".equalsIgnoreCase(stringMatcher.group(1))) {
            return rawValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        return rawValue;
    }

    /**
     * 剥离 content 中的 DSML 标记（完整块 → 孤立闭合标签 → 简化块 → 残缺片段），
     * 正常 Markdown 文本不受影响。用作总结输出前的最后安全网。
     */
    public static String strip(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String cleaned = DSML_BLOCK.matcher(content).replaceAll("");
        cleaned = DSML_CLOSING_TAG.matcher(cleaned).replaceAll("");
        cleaned = SIMPLE_BLOCK.matcher(cleaned).replaceAll("");
        cleaned = DSML_RESIDUAL.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }
}
