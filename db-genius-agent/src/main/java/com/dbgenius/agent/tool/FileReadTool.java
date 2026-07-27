package com.dbgenius.agent.tool;

import com.dbgenius.agent.tool.file.CsvParser;
import com.dbgenius.agent.tool.file.DocumentParser;
import com.dbgenius.agent.tool.file.DocxParser;
import com.dbgenius.agent.tool.file.ExcelParser;
import com.dbgenius.agent.tool.file.FileAccessGuard;
import com.dbgenius.agent.tool.file.MarkdownParser;
import com.dbgenius.agent.tool.file.PdfParser;
import com.dbgenius.model.constant.FileTypes;
import com.dbgenius.model.entity.UploadedFile;
import com.dbgenius.service.FileUploadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用文档读取工具：按 fileId 读取用户上传的文档（xlsx/xls/csv/docx/pdf/md），
 * 按扩展名分发到对应 parser，统一返回 JSON。访问控制由 {@link FileAccessGuard} 完成，
 * 所有失败均返回结构化错误文本，不抛异常打断 ReAct 循环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileReadTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final FileAccessGuard fileAccessGuard;
    private final FileUploadService fileUploadService;

    @Tool(description = "Read an uploaded document (xlsx/xls/csv/docx/pdf/md) and return its content as structured JSON. "
            + "fileId is the number from a [file#N: name] reference in the conversation.")
    public String readFile(
            @ToolParam(description = "The file ID, e.g. 12") Long fileId,
            ToolContext toolContext) {
        log.info("readFile fileId={}", fileId);

        // 全链路兜底：守卫校验（含 getOwnedFile 的 DB 访问）、下载、解析、序列化任何一步
        // 抛出异常都转为结构化错误文本，绝不逃出 tool 打断 ReAct 循环
        try {
            FileAccessGuard.GuardResult guard = fileAccessGuard.check(fileId, toolContext);
            if (!guard.ok()) {
                return guard.errorJson();
            }

            UploadedFile file = guard.file();
            String extension = FileTypes.getExtension(file.getOriginalName());
            DocumentParser parser = switch (extension) {
                case "xlsx", "xls" -> new ExcelParser();
                case "csv" -> new CsvParser();
                case "docx" -> new DocxParser();
                case "pdf" -> new PdfParser();
                case "md" -> new MarkdownParser();
                default -> null;
            };
            if (parser == null) {
                return errorJson("Unsupported file type: " + extension
                        + ". Supported: xlsx/xls/csv/docx/pdf/md. For images use readImage instead.");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("fileName", file.getOriginalName());
            result.put("format", extension);
            try (InputStream in = fileUploadService.openStream(file)) {
                result.putAll(parser.parse(in));
                return objectMapper.writeValueAsString(result);
            }
        } catch (Exception e) {
            log.error("readFile 失败 fileId={}", fileId, e);
            return errorJson("Failed to read file (fileId=" + fileId + "): " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", message);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "Error: " + message;
        }
    }
}
