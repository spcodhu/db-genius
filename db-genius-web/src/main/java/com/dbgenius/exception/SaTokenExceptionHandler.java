package com.dbgenius.exception;

import cn.dev33.satoken.exception.NotLoginException;
import com.dbgenius.common.exception.ErrorCode;
import com.dbgenius.common.i18n.MessageService;
import com.dbgenius.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class SaTokenExceptionHandler {

    private final MessageService messageService;

    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLoginException(NotLoginException e) {
        log.warn("Not login: {}", e.getMessage());
        return R.fail(ErrorCode.NOT_LOGIN.getHttpCode(),
                messageService.get(ErrorCode.NOT_LOGIN.getMessageKey()));
    }
}
