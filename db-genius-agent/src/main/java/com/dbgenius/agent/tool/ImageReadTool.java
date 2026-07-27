package com.dbgenius.agent.tool;

import com.dbgenius.agent.ocr.OcrService;
import com.dbgenius.agent.tool.file.FileAccessGuard;
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
 * 图片 OCR 读取工具：按 fileId 读取用户上传的图片（png/jpg/jpeg/webp/bmp），
 * 调 {@link OcrService} 识别文字，返回 JSON：{success, fileName, text, truncated}。
 * 安全校验链路与 {@link FileReadTool} 完全一致（复用 {@link FileAccessGuard}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageReadTool {

    /** 图片大小上限：10MB */
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** 返回给模型的最大字符数 */
    private static final int MAX_TEXT_CHARS = 30_000;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final FileAccessGuard fileAccessGuard;
    private final FileUploadService fileUploadService;
    private final OcrService ocrService;

    @Tool(description = "Recognize text in an uploaded image (png/jpg/jpeg/webp/bmp) via OCR. "
            + "fileId is the number from a [file#N: name] reference in the conversation.")
    public String readImage(
            @ToolParam(description = "The file ID, e.g. 12") Long fileId,
            ToolContext toolContext) {
        log.info("readImage fileId={}", fileId);

        // 全链路兜底：与 FileReadTool 一致，任何异常都转为结构化错误文本，不打断 ReAct 循环
        try {
            FileAccessGuard.GuardResult guard = fileAccessGuard.check(fileId, toolContext);
            if (!guard.ok()) {
                return guard.errorJson();
            }

            UploadedFile file = guard.file();
            if (!FileTypes.isImage(file.getOriginalName())) {
                return errorJson("File " + file.getOriginalName()
                        + " is not a supported image (png/jpg/jpeg/webp/bmp). For documents use readFile instead.");
            }
            if (!ocrService.isEnabled()) {
                // OCR 未启用：success=false + 提示文本，避免模型误判识别成功（recognize 对 Noop 与入参无关）
                return errorJson(ocrService.recognize(new byte[0]));
            }

            try (InputStream in = fileUploadService.openStream(file)) {
                byte[] imageBytes = in.readNBytes(MAX_IMAGE_BYTES + 1);
                if (imageBytes.length > MAX_IMAGE_BYTES) {
                    return errorJson("Image exceeds the 10MB size limit.");
                }
                String text = ocrService.recognize(imageBytes);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("fileName", file.getOriginalName());
                if (text != null && text.length() > MAX_TEXT_CHARS) {
                    result.put("text", text.substring(0, MAX_TEXT_CHARS));
                    result.put("truncated", true);
                } else {
                    result.put("text", text);
                    result.put("truncated", false);
                }
                return objectMapper.writeValueAsString(result);
            }
        } catch (Exception e) {
            log.error("readImage 失败 fileId={}", fileId, e);
            return errorJson("Failed to recognize image (fileId=" + fileId + "): " + e.getMessage());
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
