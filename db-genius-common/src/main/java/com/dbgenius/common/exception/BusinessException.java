package com.dbgenius.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    /** 关联的错误码；字面量构造（LLM 工具报错等不做 i18n 的场景）为 null */
    private final ErrorCode errorCode;

    /** 消息占位符 {0}/{1} 的填充参数，无则为 null */
    private final Object[] args;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
        this.errorCode = null;
        this.args = null;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.errorCode = null;
        this.args = null;
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, (Object[]) null);
    }

    /**
     * 基于错误码的构造：message 暂存 messageKey（日志可追溯），
     * 面向用户的本地化文案由全局异常处理器按请求 locale 解析。
     */
    public BusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode.getMessageKey());
        this.code = errorCode.getHttpCode();
        this.errorCode = errorCode;
        this.args = args;
    }
}
