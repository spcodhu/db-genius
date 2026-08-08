package com.dbgenius.service.modelinfo;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 内置已知模型上下文窗口注册表。
 *
 * <p>OpenAI 兼容协议的 /v1/models 标准上不返回上下文大小，远程探测只能验证模型存在，
 * 因此以本地注册表为主要数据源；未收录的模型由用户手动填写。
 * 匹配规则：忽略大小写精确匹配 → 前缀匹配。后续可平滑迁移到数据库表。
 */
@Component
public class KnownModelRegistry {

    /** 精确匹配（小写模型名 → 上下文窗口 token 数） */
    private static final Map<String, Integer> EXACT = Map.ofEntries(
            Map.entry("deepseek-chat", 65536),
            Map.entry("deepseek-reasoner", 65536),
            Map.entry("deepseek-v3", 65536),
            Map.entry("deepseek-v4-pro", 65536),
            Map.entry("gpt-4-turbo", 131072),
            Map.entry("gpt-3.5-turbo", 16385),
            Map.entry("qwen-max", 32768),
            Map.entry("qwen-plus", 131072),
            Map.entry("qwen-turbo", 131072),
            Map.entry("glm-4", 131072),
            Map.entry("glm-4-plus", 131072),
            Map.entry("moonshot-v1-32k", 32768),
            Map.entry("moonshot-v1-128k", 131072)
    );

    /** 前缀匹配（按顺序，先命中先得） */
    private static final List<Map.Entry<String, Integer>> PREFIXES = List.of(
            Map.entry("gpt-4.1", 1047576),
            Map.entry("gpt-4o", 131072),
            Map.entry("o3", 200000),
            Map.entry("o4-mini", 200000),
            Map.entry("deepseek", 65536),
            Map.entry("kimi", 131072),
            Map.entry("glm", 131072),
            Map.entry("qwen", 131072)
    );

    /**
     * 按模型名查找上下文窗口大小。
     *
     * @return 命中返回窗口大小；未收录返回空（调用方应引导用户手填，不要猜测）
     */
    public Optional<Integer> lookup(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        String name = modelName.strip().toLowerCase(Locale.ROOT);
        Integer exact = EXACT.get(name);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Map.Entry<String, Integer> entry : PREFIXES) {
            if (name.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }
}
