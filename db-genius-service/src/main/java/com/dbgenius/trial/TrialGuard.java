package com.dbgenius.trial;

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
     * 试用版下直接拒绝，使用自定义提示
     */
    public void denyIfTrial(String message) {
        if (isTrialMode()) {
            throw new TrialBusinessException(message);
        }
    }

    /**
     * 如果处于试用版且目标配置是内置数据库，则拒绝
     */
    public void denyIfTrialBuiltin(DbConfig config) {
        if (isTrialMode() && config != null && Boolean.TRUE.equals(config.getBuiltin())) {
            throw new TrialBusinessException("试用版暂不支持修改内置数据库配置");
        }
    }

    /**
     * 判断指定配置是否为试用版内置数据库
     */
    public boolean isBuiltin(DbConfig config) {
        return config != null && Boolean.TRUE.equals(config.getBuiltin());
    }
}
