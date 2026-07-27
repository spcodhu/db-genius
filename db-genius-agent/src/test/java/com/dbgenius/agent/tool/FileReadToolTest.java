package com.dbgenius.agent.tool;

import com.dbgenius.agent.tool.file.FileAccessGuard;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.entity.UploadedFile;
import com.dbgenius.service.FileUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FileReadTool 单元测试：覆盖 fileId 白名单拒绝、ToolContext 安全上下文缺失、
 * 属主二次校验越权、正常 md 读取与不支持扩展名分发。
 * 使用真实 FileAccessGuard（仅 mock FileUploadService）以覆盖完整校验链路。
 */
class FileReadToolTest {

    private final FileUploadService fileUploadService = mock(FileUploadService.class);
    private final FileAccessGuard fileAccessGuard = new FileAccessGuard(fileUploadService);
    private final FileReadTool tool = new FileReadTool(fileAccessGuard, fileUploadService);

    private ToolContext contextWith(Long userId, Set<Long> allowedFileIds) {
        return new ToolContext(Map.of(
                FileAccessGuard.CONTEXT_USER_ID, userId,
                FileAccessGuard.CONTEXT_ALLOWED_FILE_IDS, allowedFileIds));
    }

    private UploadedFile mdFile(Long id, Long userId) {
        UploadedFile file = new UploadedFile();
        file.setId(id);
        file.setUserId(userId);
        file.setOriginalName("notes.md");
        file.setOssKey("uploads/" + userId + "/abc.md");
        return file;
    }

    @Test
    void shouldRejectFileIdNotInAllowedList() {
        ToolContext context = contextWith(1L, Set.of(12L));

        String result = tool.readFile(999L, context);

        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("not among the files referenced"));
        verify(fileUploadService, never()).getOwnedFile(any(), any());
    }

    @Test
    void shouldRejectWhenSecurityContextMissing() {
        // ToolContext 缺 userId/allowedFileIds（或整个为 null）都必须拒绝，不抛异常
        String noKeys = tool.readFile(12L, new ToolContext(Map.of()));
        assertTrue(noKeys.contains("\"success\":false"));
        assertTrue(noKeys.contains("missing security context"));

        String nullContext = tool.readFile(12L, null);
        assertTrue(nullContext.contains("\"success\":false"));
        assertTrue(nullContext.contains("missing security context"));

        verify(fileUploadService, never()).getOwnedFile(any(), any());
    }

    @Test
    void shouldRejectWhenOwnershipCheckFails() {
        // 白名单通过但 getOwnedFile 抛 403（防御 toolContext 被误构建）
        when(fileUploadService.getOwnedFile(12L, 1L))
                .thenThrow(new BusinessException(403, "No permission to access this file"));

        String result = tool.readFile(12L, contextWith(1L, Set.of(12L)));

        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("No permission to access this file"));
    }

    @Test
    void shouldReadMarkdownFileSuccessfully() {
        UploadedFile file = mdFile(12L, 1L);
        when(fileUploadService.getOwnedFile(12L, 1L)).thenReturn(file);
        when(fileUploadService.openStream(file))
                .thenReturn(new ByteArrayInputStream("# Hello\nworld".getBytes(StandardCharsets.UTF_8)));

        String result = tool.readFile(12L, contextWith(1L, Set.of(12L)));

        assertTrue(result.contains("\"success\":true"));
        assertTrue(result.contains("\"format\":\"md\""));
        assertTrue(result.contains("# Hello"));
        assertTrue(result.contains("\"truncated\":false"));
    }

    @Test
    void shouldRejectUnsupportedExtension() {
        UploadedFile file = mdFile(12L, 1L);
        file.setOriginalName("archive.zip");
        when(fileUploadService.getOwnedFile(12L, 1L)).thenReturn(file);

        String result = tool.readFile(12L, contextWith(1L, Set.of(12L)));

        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Unsupported file type"));
        verify(fileUploadService, never()).openStream(any());
    }
}
