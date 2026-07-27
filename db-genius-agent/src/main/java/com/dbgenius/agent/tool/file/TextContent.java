package com.dbgenius.agent.tool.file;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 纯文本内容的截断封装，docx/pdf/md parser 共用。
 */
final class TextContent {

    /** 返回给模型的最大字符数 */
    static final int MAX_CHARS = 30_000;

    private TextContent() {
    }

    /**
     * 包装纯文本：content/truncated/totalChars，超过 {@value #MAX_CHARS} 字符截断。
     */
    static Map<String, Object> of(String text) {
        if (text == null) {
            text = "";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalChars", text.length());
        if (text.length() > MAX_CHARS) {
            result.put("content", text.substring(0, MAX_CHARS));
            result.put("truncated", true);
            result.put("message", "Only first " + MAX_CHARS + " chars returned. Total: " + text.length());
        } else {
            result.put("content", text);
            result.put("truncated", false);
        }
        return result;
    }
}
