package com.dbgenius.agent.tool.file;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Markdown 解析器：按 UTF-8 读取纯文本。
 *
 * <p>内存防护：限量读取 {@link #BYTE_LIMIT} 字节而非整文件入内存。UTF-8 单字符最多 4 字节，
 * 读满 {@code MAX_CHARS * 4 + 1} 字节即可保证解码后超过 {@link TextContent#MAX_CHARS} 字符，
 * 截断判定（truncated）由 {@link TextContent#of} 统一完成；不足上限即为全量内容。</p>
 */
public class MarkdownParser implements DocumentParser {

    /** 读取字节上限：{@link TextContent#MAX_CHARS} 个字符的 UTF-8 最大字节数 + 1（用于探测超长） */
    private static final int BYTE_LIMIT = TextContent.MAX_CHARS * 4 + 1;

    @Override
    public Map<String, Object> parse(InputStream in) throws Exception {
        return TextContent.of(new String(in.readNBytes(BYTE_LIMIT), StandardCharsets.UTF_8));
    }
}
