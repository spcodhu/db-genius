package com.dbgenius.agent.tool.file;

import java.io.InputStream;
import java.util.Map;

/**
 * 文档解析器：从输入流抽取内容，返回结果字段（不含 success/fileName/format 等公共字段，
 * 由 FileReadTool 统一补齐并序列化为 JSON）。
 */
public interface DocumentParser {

    /**
     * 解析文档内容。
     *
     * @param in 文件输入流，<b>由调用方负责关闭</b>
     * @return 解析结果字段（各 parser 自行控制截断并标注 truncated/message）
     */
    Map<String, Object> parse(InputStream in) throws Exception;
}
