package com.dbgenius.agent.tool.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link ToolOutputGuard}：
 * 1. 未超限时原样返回；
 * 2. 行集型输出按行裁剪，产出仍是<b>合法 JSON</b>且保留元信息字段；
 * 3. 非行集型输出封装为合法 JSON 信封（不把腰斩的非法 JSON 丢给模型）；
 * 4. 完整原文登记进制品仓，截断提示中带 artifactId 与统一标记前缀；
 * 5. 无 taskId 时降级为「无取回句柄」的纯截断，不抛异常。
 */
class ToolOutputGuardTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private ToolOutputArtifactStore store;
    private ToolOutputGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        store = new ToolOutputArtifactStore();
        guard = new ToolOutputGuard(store);
        setField("maxChars", 1200);
        setField("maxRows", 50);
    }

    private void setField(String name, int value) throws Exception {
        Field field = ToolOutputGuard.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(guard, value);
    }

    private String rowSetJson(int rows) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("rowCount", rows);
        result.put("data", IntStream.range(0, rows)
                .mapToObj(i -> Map.of("id", i, "name", "user-" + i, "remark", "x".repeat(40)))
                .toList());
        return objectMapper.writeValueAsString(result);
    }

    @Test
    void shouldReturnOutputUnchangedWhenWithinLimit() {
        String small = "{\"success\":true,\"rowCount\":1,\"data\":[{\"id\":1}]}";
        assertThat(guard.guard("task-1", "executeSql", small)).isSameAs(small);
    }

    @Test
    void shouldTruncateRowSetByRowsAndStayValidJson() throws Exception {
        String raw = rowSetJson(200);

        String guarded = guard.guard("task-1", "executeSql", raw);

        assertThat(guarded).isNotEqualTo(raw);
        JsonNode node = objectMapper.readTree(guarded);
        assertThat(node.get("success").asBoolean()).isTrue();
        // 非数组的元信息字段原样保留，模型看到的结构与未截断时一致
        assertThat(node.get("rowCount").asInt()).isEqualTo(200);
        assertThat(node.get("truncated").asBoolean()).isTrue();
        assertThat(node.get("truncatedReason").asText()).isEqualTo("TOOL_OUTPUT_TOO_LONG");
        assertThat(node.get("totalItems").asInt()).isEqualTo(200);
        assertThat(node.get("data").isArray()).isTrue();
        assertThat(node.get("data").size())
                .isEqualTo(node.get("returnedItems").asInt())
                .isLessThan(200);
        assertThat(node.get("notice").asText()).contains(ToolOutputGuard.TRUNCATED_PREFIX);
    }

    @Test
    void shouldWrapNonRowSetOutputIntoValidJsonEnvelope() throws Exception {
        String raw = "plain text ".repeat(500);

        String guarded = guard.guard("task-1", "readFile", raw);

        JsonNode node = objectMapper.readTree(guarded);
        assertThat(node.get("truncated").asBoolean()).isTrue();
        assertThat(node.get("originalChars").asInt()).isEqualTo(raw.length());
        assertThat(node.get("omittedChars").asInt()).isPositive();
        assertThat(node.get("preview").asText()).startsWith("plain text");
        assertThat(node.get("notice").asText()).contains(ToolOutputGuard.TRUNCATED_PREFIX);
    }

    @Test
    void shouldRegisterFullOutputAndExposeRetrievableArtifactId() throws Exception {
        String raw = rowSetJson(200);

        JsonNode node = objectMapper.readTree(guard.guard("task-1", "executeSql", raw));

        String artifactId = node.get("artifactId").asText();
        assertThat(node.get("notice").asText()).contains("readToolOutput").contains(artifactId);

        ToolOutputArtifactStore.Slice slice = store.read("task-1", artifactId, 0, 100);
        assertThat(slice).isNotNull();
        assertThat(slice.total()).isEqualTo(raw.length());
        assertThat(slice.content()).isEqualTo(raw.substring(0, 100));
        assertThat(slice.hasMore()).isTrue();
    }

    @Test
    void shouldStillTruncateWithoutTaskIdButOmitRetrievalHint() throws Exception {
        String raw = rowSetJson(200);

        JsonNode node = objectMapper.readTree(guard.guard(null, "executeSql", raw));

        assertThat(node.get("truncated").asBoolean()).isTrue();
        assertThat(node.has("artifactId")).isFalse();
        assertThat(node.get("notice").asText()).doesNotContain("readToolOutput");
    }

    @Test
    void shouldApplyPerToolOverride() throws Exception {
        Field field = ToolOutputGuard.class.getDeclaredField("perToolMaxCharsConfig");
        field.setAccessible(true);
        field.set(guard, "compareDatabases=100000, bogus=abc");

        String raw = rowSetJson(200);
        assertThat(raw.length()).isBetween(1201, 100000);
        // 覆盖上限远大于原文长度，该工具不触发截断
        assertThat(guard.guard("task-1", "compareDatabases", raw)).isSameAs(raw);
        // 其他工具仍走默认上限
        assertThat(guard.guard("task-1", "executeSql", raw)).isNotEqualTo(raw);
    }

    @Test
    void shouldEvictArtifactsWhenTaskFinishes() throws Exception {
        JsonNode node = objectMapper.readTree(guard.guard("task-1", "executeSql", rowSetJson(200)));
        String artifactId = node.get("artifactId").asText();
        assertThat(store.read("task-1", artifactId, 0, 10)).isNotNull();

        guard.evictTask("task-1");

        assertThat(store.read("task-1", artifactId, 0, 10)).isNull();
    }

    @Test
    void shouldIsolateArtifactsAcrossTasks() throws Exception {
        JsonNode node = objectMapper.readTree(guard.guard("task-1", "executeSql", rowSetJson(200)));
        String artifactId = node.get("artifactId").asText();

        assertThat(store.read("task-2", artifactId, 0, 10)).isNull();
    }

    @Test
    void shouldKeepOnlyTheMostRecentArtifactsPerTask() throws Exception {
        Field maxPerTask = ToolOutputArtifactStore.class.getDeclaredField("maxPerTask");
        maxPerTask.setAccessible(true);
        maxPerTask.setInt(store, 2);

        List<String> ids = IntStream.range(0, 3)
                .mapToObj(i -> store.register("task-1", "executeSql", "payload-" + i))
                .toList();

        assertThat(store.read("task-1", ids.get(0), 0, 10)).isNull();
        assertThat(store.read("task-1", ids.get(2), 0, 100).content()).isEqualTo("payload-2");
    }
}
