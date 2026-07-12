package com.dbgenius.common.exception;

import lombok.Getter;

/**
 * 试用版业务异常。
 * 当试用版中触发了被限制的操作时抛出，由全局异常处理器统一返回前端。
 */
@Getter
public class TrialBusinessException extends BusinessException {

    private static final int DEFAULT_CODE = 403;
    private static final String DEFAULT_MESSAGE = "试用版暂不支持该操作";

    public TrialBusinessException() {
        super(DEFAULT_CODE, DEFAULT_MESSAGE);
    }

    public TrialBusinessException(String message) {
        super(DEFAULT_CODE, message);
    }
}
