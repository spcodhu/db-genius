package com.dbgenius.trial;

import com.dbgenius.common.exception.ErrorCode;
import com.dbgenius.common.exception.TrialBusinessException;
import com.dbgenius.model.entity.DbConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 试用版权限校验辅助类。
 */
@Component
@RequiredArgsConstructor
public class TrialGuard {

    private final TrialProperties trialProperties;

    /**
     * 当前是否处于试用版模式
     */
    public boolean isTrialMode() {
        return trialProperties.isEnabled();
    }

    /**
     * 试用版下直接拒绝
     */
    public void denyIfTrial() {
        if (isTrialMode()) {
            throw new TrialBusinessException();
        }
    }

    /**
     * 试用版下直接拒绝，使用指定错误码（文案按请求 locale 解析）
     */
    public void denyIfTrial(ErrorCode errorCode) {
        if (isTrialMode()) {
            throw new TrialBusinessException(errorCode);
        }
    }

    /**
     * 如果处于试用版且目标配置是内置数据库，则拒绝
     */
    public void denyIfTrialBuiltin(DbConfig config) {
        if (isTrialMode() && config != null && Boolean.TRUE.equals(config.getBuiltin())) {
            throw new TrialBusinessException(ErrorCode.TRIAL_BUILTIN_MODIFY);
        }
    }

    /**
     * 判断指定配置是否为试用版内置数据库
     */
    public boolean isBuiltin(DbConfig config) {
        return config != null && Boolean.TRUE.equals(config.getBuiltin());
    }
}
