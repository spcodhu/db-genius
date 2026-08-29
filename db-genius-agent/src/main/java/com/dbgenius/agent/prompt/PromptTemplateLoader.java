package com.dbgenius.agent.prompt;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 提示词模板加载器：从 classpath {@code prompts/{name}_{locale}.md} 加载模板。
 *
 * <p><b>回退链：</b>{@code _{locale}} → {@code _en} 两级。仓库只维护 zh_CN 与 en 两套模板
 * （LLM 对英文 prompt 遵循度最好），es/fr/ja/ms/zh_TW 一律回退 en 模板，
 * 输出语言由 {@link #withOutputLanguage} 追加的显式指令保证。</p>
 *
 * <p><b>模板约定：</b>
 * <ul>
 *   <li>占位符写作 {@code {placeholder}}，由 {@link #render} 做纯文本替换（非 MessageFormat）；</li>
 *   <li>同时含 system 与 user 两段的模板（如 intent-classifier），用单独一行
 *       {@code ===USER===} 分隔，由 {@link #splitSections} 拆分。</li>
 * </ul>
 *
 * <p>加载结果按 name+locale 缓存（模板运行期不变）。</p>
 */
public final class PromptTemplateLoader {

    private static final String BASE_PATH = "prompts/";
    private static final String SUFFIX = ".md";
    private static final String FALLBACK_VARIANT = "en";

    /** system 段与 user 段的分隔标记（独占一行） */
    private static final String USER_SECTION_DELIMITER = "===USER===";

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** locale → 输出语言显式指令使用的语言名 */
    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "zh-CN", "Simplified Chinese",
            "zh-TW", "Traditional Chinese",
            "en", "English",
            "es", "Spanish",
            "fr", "French",
            "ja", "Japanese",
            "ms", "Malay");

    private PromptTemplateLoader() {
        // 工具类禁止实例化
    }

    /**
     * 加载模板内容（含回退与缓存）。
     *
     * @param name   模板名（不含语言后缀与扩展名，如 "db-sql-agent-system"）
     * @param locale 目标语言环境，null 时按 en 处理
     */
    public static String load(String name, Locale locale) {
        String variant = resolveVariant(name, locale);
        return CACHE.computeIfAbsent(name + "_" + variant, key -> readResource(BASE_PATH + key + SUFFIX));
    }

    /**
     * 解析实际命中的模板语言变体（"zh_CN" / "en"），供调用方按变体选择配套措辞
     * （如对话历史的角色标签）。
     */
    public static String resolveVariant(String name, Locale locale) {
        String tag = locale == null ? "" : locale.toString();
        if (!tag.isEmpty() && new ClassPathResource(BASE_PATH + name + "_" + tag + SUFFIX).exists()) {
            return tag;
        }
        return FALLBACK_VARIANT;
    }

    /**
     * 按 {@code ===USER===} 分隔符拆分模板：返回 [system] 或 [system, user]。
     */
    public static String[] splitSections(String template) {
        String[] parts = template.split("\\n" + USER_SECTION_DELIMITER + "\\n");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].strip();
        }
        return parts;
    }

    /**
     * 纯文本占位符替换：{@code render(template, Map.of("schema", "..."))} 把
     * 模板中的 {@code {schema}} 替换为对应值。未提供的占位符原样保留。
     */
    public static String render(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * locale 映射为输出语言名（zh_CN→Simplified Chinese、zh_TW→Traditional Chinese 等），
     * 未识别的 locale 按 English 处理。
     */
    public static String outputLanguageName(Locale locale) {
        if (locale == null) {
            return "English";
        }
        return LANGUAGE_NAMES.getOrDefault(locale.toLanguageTag(), "English");
    }

    /**
     * 在 system prompt 尾部拼接显式输出语言指令，替代 "Respond in the same language
     * as the user" 之类的软约束。
     */
    public static String withOutputLanguage(String systemPrompt, Locale locale) {
        return systemPrompt + "\n\nYou MUST respond in " + outputLanguageName(locale) + ".";
    }

    private static String readResource(String path) {
        try {
            return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Prompt template not found: " + path, e);
        }
    }
}
