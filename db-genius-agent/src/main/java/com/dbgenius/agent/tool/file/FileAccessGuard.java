package com.dbgenius.agent.tool.file;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.entity.UploadedFile;
import com.dbgenius.service.FileUploadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件类 tool 的统一访问守卫：从 {@link ToolContext} 取身份与会话内文件白名单做包含性检查，
 * 再走 {@link FileUploadService#getOwnedFile} 做属主二次校验（防御 toolContext 被误构建）。
 *
 * <p>所有拒绝均返回结构化错误文本（JSON），<b>不抛异常</b>——tool 抛异常会打断 ReAct 循环，
 * 返回错误文本可让模型自我修正。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileAccessGuard {

    /** ToolContext key：当前登录用户 ID（Long） */
    public static final String CONTEXT_USER_ID = "userId";

    /** ToolContext key：本轮会话允许访问的文件 ID 集合（Collection&lt;Long&gt;） */
    public static final String CONTEXT_ALLOWED_FILE_IDS = "allowedFileIds";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final FileUploadService fileUploadService;

    /**
     * 校验链路：ToolContext 身份与白名单 → fileId 包含性检查 → getOwnedFile 属主二次校验。
     *
     * @return 全部通过返回 {@link GuardResult#ok} 结果，任一拒绝返回带 errorJson 的结果
     */
    public GuardResult check(Long fileId, ToolContext toolContext) {
        Map<String, Object> context = toolContext != null ? toolContext.getContext() : null;
        Object userIdObj = context != null ? context.get(CONTEXT_USER_ID) : null;
        Object allowedObj = context != null ? context.get(CONTEXT_ALLOWED_FILE_IDS) : null;
        if (!(userIdObj instanceof Number) || !(allowedObj instanceof Collection<?> allowedFileIds)) {
            log.warn("文件访问被拒绝：ToolContext 缺少 userId/allowedFileIds, fileId={}", fileId);
            return GuardResult.deny("File access denied: missing security context.");
        }
        Long userId = ((Number) userIdObj).longValue();

        boolean inWhitelist = fileId != null && allowedFileIds.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .anyMatch(id -> id.longValue() == fileId);
        if (!inWhitelist) {
            log.warn("文件访问被拒绝：fileId={} 不在会话白名单 {}, userId={}", fileId, allowedFileIds, userId);
            return GuardResult.deny("File access denied: fileId=" + fileId
                    + " is not among the files referenced in this conversation.");
        }

        try {
            return GuardResult.ok(fileUploadService.getOwnedFile(fileId, userId));
        } catch (BusinessException e) {
            log.warn("文件访问被拒绝：getOwnedFile 校验未通过, fileId={}, userId={}, reason={}",
                    fileId, userId, e.getMessage());
            return GuardResult.deny("File access denied: " + e.getMessage());
        }
    }

    /**
     * 守卫结果：{@code errorJson == null} 表示通过，否则为可直接返回给模型的拒绝文本。
     */
    public record GuardResult(UploadedFile file, String errorJson) {

        public boolean ok() {
            return errorJson == null;
        }

        static GuardResult ok(UploadedFile file) {
            return new GuardResult(file, null);
        }

        static GuardResult deny(String reason) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", reason);
            try {
                return new GuardResult(null, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                return new GuardResult(null, "Error: " + reason);
            }
        }
    }
}
