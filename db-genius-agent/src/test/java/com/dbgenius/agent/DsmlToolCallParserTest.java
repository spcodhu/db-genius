package com.dbgenius.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DsmlToolCallParserTest {

    // DSML 标签一律经拼接构造，避免源码中的字面标签串干扰文本处理工具
    private static String param(String name, boolean string, String value) {
        return "<｜｜DSML｜｜" + "parameter name=\"" + name + "\" string=\"" + string + "\">"
                + value + "<" + "/｜｜DSML｜｜parameter>\n";
    }

    private static String invoke(String name, String paramsBody) {
        return "<｜｜DSML｜｜" + "invoke name=\"" + name + "\">\n"
                + paramsBody + "<" + "/｜｜DSML｜｜invoke>\n";
    }

    private static String toolCalls(String invokes) {
        return "<｜｜DSML｜｜" + "tool_calls>\n" + invokes + "<" + "/｜｜DSML｜｜tool_calls>";
    }

    /** 线上日志原样的 DSML（全角竖线、string 属性齐全） */
    private static final String LOG_SAMPLE = toolCalls(invoke("executeSql",
            param("dbConfigId", false, "1") + param("sql", true, "SHOW TABLES")));

    @Test
    void shouldParseRealLogSampleWithCorrectTypes() {
        List<DsmlToolCallParser.RecoveredToolCall> calls = DsmlToolCallParser.parse(LOG_SAMPLE);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).name()).isEqualTo("executeSql");
        // string="false" 的数值参数不加引号；string="true" 的保持字符串
        assertThat(calls.get(0).argumentsJson()).isEqualTo("{\"dbConfigId\":1,\"sql\":\"SHOW TABLES\"}");
    }

    @Test
    void shouldTypeParametersAccordingToStringAttribute() {
        String content = invoke("demo",
                param("s", true, "42")
                        + param("n", false, "42")
                        + param("b", false, "true")
                        + param("f", false, "not-a-number"));

        List<DsmlToolCallParser.RecoveredToolCall> calls = DsmlToolCallParser.parse(content);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).argumentsJson())
                .isEqualTo("{\"s\":\"42\",\"n\":42,\"b\":true,\"f\":\"not-a-number\"}");
    }

    @Test
    void shouldParseMultipleInvokesIncludingSimplifiedTags() {
        String content = "前缀文本\n"
                + invoke("a", param("x", true, "1"))
                + "<" + "invoke name=\"echo\">\n"
                + "<" + "parameter name=\"text\" string=\"true\">hello<" + "/parameter>\n"
                + "<" + "/invoke>\n"
                + "后缀文本";

        List<DsmlToolCallParser.RecoveredToolCall> calls = DsmlToolCallParser.parse(content);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).name()).isEqualTo("a");
        assertThat(calls.get(0).argumentsJson()).isEqualTo("{\"x\":\"1\"}");
        assertThat(calls.get(1).name()).isEqualTo("echo");
        assertThat(calls.get(1).argumentsJson()).isEqualTo("{\"text\":\"hello\"}");
    }

    @Test
    void shouldStripDsmlWithoutHurtingMarkdown() {
        String markdown = "## 查询结果\n\n| 表名 |\n|---|\n| user |\n\n共 1 张表。";
        String cleaned = DsmlToolCallParser.strip(markdown + "\n\n" + LOG_SAMPLE + "\n后续正文");

        assertThat(cleaned).contains("## 查询结果").contains("| user |").contains("后续正文");
        assertThat(cleaned).doesNotContain("DSML").doesNotContain("invoke").doesNotContain("SHOW TABLES");
    }

    @Test
    void shouldStripResidualAndClosingTags() {
        String content = "结论如下\n<｜｜DSML｜｜" + "tool_calls>\n残缺内容没有闭合";
        String cleaned = DsmlToolCallParser.strip(content);
        assertThat(cleaned).doesNotContain("DSML");
        assertThat(cleaned).contains("结论如下").contains("残缺内容没有闭合");
    }

    @Test
    void shouldDetectDsmlPresence() {
        assertThat(DsmlToolCallParser.containsDsml(LOG_SAMPLE)).isTrue();
        assertThat(DsmlToolCallParser.containsDsml("普通 Markdown，没有标记")).isFalse();
        assertThat(DsmlToolCallParser.containsDsml(null)).isFalse();
    }

    @Test
    void shouldReturnEmptyForBlankOrNoInvoke() {
        assertThat(DsmlToolCallParser.parse(null)).isEmpty();
        assertThat(DsmlToolCallParser.parse("")).isEmpty();
        assertThat(DsmlToolCallParser.parse("只有文字，没有标记")).isEmpty();
    }
}
