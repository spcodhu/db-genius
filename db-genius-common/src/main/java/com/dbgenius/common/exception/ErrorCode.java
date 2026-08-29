package com.dbgenius.common.exception;

import lombok.Getter;

/**
 * 用户可见报错的统一错误码枚举。
 *
 * <p><b>使用约定：</b>凡是要展示给最终用户的报错，一律抛
 * {@code new BusinessException(ErrorCode, args...)}，文案由全局异常处理器按请求 locale
 * 从 messages.properties 解析（messageKey 中的 {0}/{1} 由 args 填充）。
 * 给 LLM 看的工具内部报错（{@code SqlExecuteTool} 等）不做 i18n，继续使用字面量构造器。</p>
 */
@Getter
public enum ErrorCode {

    // ---- 兜底 ----
    INTERNAL_ERROR(500, "error.internal"),

    // ---- 认证与用户 ----
    INVALID_CREDENTIALS(401, "error.auth.invalidCredentials"),
    ACCOUNT_DISABLED(403, "error.auth.accountDisabled"),
    NOT_LOGIN(401, "error.auth.notLogin"),
    ADMIN_ONLY(403, "error.auth.adminOnly"),
    USERNAME_EXISTS(400, "error.user.usernameExists"),

    // ---- 文件 ----
    FILE_EMPTY(400, "error.file.empty"),
    /** args: {0}=允许的文档扩展名, {1}=允许的图片扩展名 */
    FILE_TYPE_NOT_ALLOWED(400, "error.file.typeNotAllowed"),
    /** args: {0}=大小上限（MB） */
    FILE_TOO_LARGE(400, "error.file.tooLarge"),
    /** args: {0}=底层失败原因 */
    FILE_UPLOAD_FAILED(500, "error.file.uploadFailed"),
    FILE_NOT_FOUND(404, "error.file.notFound"),
    FILE_NO_PERMISSION(403, "error.file.noPermission"),

    // ---- 模型配置 ----
    CANNOT_DELETE_DEFAULT_CONFIG(400, "error.modelConfig.cannotDeleteDefault"),
    DISABLED_CONFIG_CANNOT_BE_DEFAULT(400, "error.modelConfig.disabledCannotBeDefault"),
    NO_MODEL_CONFIGURED(400, "error.modelConfig.notConfigured"),
    MODEL_CONFIG_NOT_FOUND(404, "error.modelConfig.notFound"),

    // ---- 数据库配置与适配器 ----
    DB_CONFIG_NOT_FOUND(404, "error.dbConfig.notFound"),
    DB_CONFIG_VERIFYING(400, "error.dbConfig.verifying"),
    DB_CONFIG_CONNECTION_FAILED(400, "error.dbConfig.connectionFailed"),
    DOC_NOT_GENERATED(400, "error.dbConfig.docNotGenerated"),
    /** args: {0}=不支持的类型编码 */
    UNSUPPORTED_DB_TYPE(400, "error.db.unsupportedType"),
    /** args: {0}=类型编码 */
    NO_ADAPTER(400, "error.db.noAdapter"),
    /** args: {0}=字段名, {1}=数据库类型展示名 */
    DB_FIELD_REQUIRED(400, "error.db.fieldRequired"),

    // ---- 会话 ----
    CONVERSATION_NOT_FOUND(404, "error.conversation.notFound"),

    // ---- 对话前置条件 ----
    SQL_QUERY_NO_DB_CONFIG(400, "error.chat.sqlQueryNoDbConfig"),
    WORKFLOW_NO_DB_CONFIG(400, "error.chat.workflowNoDbConfig"),
    COMPARE_NO_DB_CONFIG(400, "error.chat.compareNoDbConfig"),

    // ---- 试用版限制 ----
    TRIAL_DENIED(403, "error.trial.denied"),
    TRIAL_BUILTIN_MODIFY(403, "error.trial.builtinModify"),
    TRIAL_CREATE_USER(403, "error.trial.createUser"),
    TRIAL_FILE_UPLOAD(403, "error.trial.fileUpload"),
    TRIAL_DB_CONFIG_CREATE(403, "error.trial.dbConfigCreate"),
    TRIAL_MODEL_CONFIG_CREATE(403, "error.trial.modelConfigCreate"),
    TRIAL_MODEL_CONFIG_UPDATE(403, "error.trial.modelConfigUpdate"),
    TRIAL_MODEL_CONFIG_DELETE(403, "error.trial.modelConfigDelete"),
    TRIAL_MODEL_CONFIG_SET_DEFAULT(403, "error.trial.modelConfigSetDefault"),
    TRIAL_CONTEXT_WINDOW_LOOKUP(403, "error.trial.contextWindowLookup"),
    TRIAL_WORKFLOW(403, "error.trial.workflow"),
    TRIAL_DB_COMPARE(403, "error.trial.dbCompare");

    /** HTTP 语义状态码（写入 R.code） */
    private final int httpCode;

    /** messages.properties 中的文案键 */
    private final String messageKey;

    ErrorCode(int httpCode, String messageKey) {
        this.httpCode = httpCode;
        this.messageKey = messageKey;
    }
}
