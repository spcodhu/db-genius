package com.dbgenius.common.exception;

/**
 * 试用版业务异常。
 * 当试用版中触发了被限制的操作时抛出，由全局异常处理器统一返回前端。
 */
public class TrialBusinessException extends BusinessException {

    private static final int DEFAULT_CODE = 403;

    public TrialBusinessException() {
        super(ErrorCode.TRIAL_DENIED);
    }

    public TrialBusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 字面量提示构造器：保留给不需要 i18n 的内部场景。
     */
    public TrialBusinessException(String message) {
        super(DEFAULT_CODE, message);
    }
}
