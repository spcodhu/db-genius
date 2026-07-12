package com.dbgenius.trial;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 试用版配置项。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "db-genius.trial")
public class TrialProperties {

    /**
     * 是否启用试用版模式
     */
    private boolean enabled = false;

    /**
     * 内置测试数据库名称
     */
    private String builtinDbName = "db-genius";

    /**
     * 内置测试数据库主机
     */
    private String builtinHost;

    /**
     * 内置测试数据库端口
     */
    private Integer builtinPort = 3306;

    /**
     * 内置测试数据库用户名
     */
    private String builtinUsername;

    /**
     * 内置测试数据库密码
     */
    private String builtinPassword;
}
