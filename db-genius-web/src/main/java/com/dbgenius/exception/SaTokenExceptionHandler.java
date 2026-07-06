package com.dbgenius.exception;

import cn.dev33.satoken.exception.NotLoginException;
import com.dbgenius.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class SaTokenExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLoginException(NotLoginException e) {
        log.warn("Not login: {}", e.getMessage());
        return R.fail(401, "登录已过期，请重新登录");
    }
}
