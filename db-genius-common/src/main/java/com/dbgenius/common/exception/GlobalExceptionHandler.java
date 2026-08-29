package com.dbgenius.common.exception;

import com.dbgenius.common.i18n.MessageService;
import com.dbgenius.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：统一返回出口。
 *
 * <p>带 {@link ErrorCode} 的 {@link BusinessException} 在此按请求 locale 解析成本地化文案；
 * 字面量构造的异常（LLM 工具报错等）原样透传 message。日志始终记录原始信息（messageKey 或字面量）。</p>
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageService messageService;

    @ExceptionHandler(TrialBusinessException.class)
    public R<Void> handleTrialBusinessException(TrialBusinessException e) {
        log.warn("Trial operation denied: {}", e.getMessage());
        return R.fail(e.getCode(), resolveMessage(e));
    }

    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        return R.fail(e.getCode(), resolveMessage(e));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return R.fail(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("Unexpected error", e);
        return R.fail(ErrorCode.INTERNAL_ERROR.getHttpCode(),
                messageService.get(ErrorCode.INTERNAL_ERROR.getMessageKey(), LocaleContextHolder.getLocale()));
    }

    /** 带 ErrorCode 的异常按当前请求 locale 解析文案；字面量异常原样透传。 */
    private String resolveMessage(BusinessException e) {
        if (e.getErrorCode() == null) {
            return e.getMessage();
        }
        return messageService.get(e.getErrorCode().getMessageKey(), LocaleContextHolder.getLocale(), e.getArgs());
    }
}
