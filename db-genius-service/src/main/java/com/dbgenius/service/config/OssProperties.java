package com.dbgenius.service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 配置项。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "db-genius.oss")
public class OssProperties {

    /**
     * OSS endpoint，如 https://oss-cn-hangzhou.aliyuncs.com
     */
    private String endpoint;

    /**
     * OSS bucket 名称
     */
    private String bucket;

    /**
     * RAM 子账号 AccessKey ID
     */
    private String accessKeyId;

    /**
     * RAM 子账号 AccessKey Secret
     */
    private String accessKeySecret;

    /**
     * 对象 key 的统一前缀
     */
    private String dirPrefix = "uploads/";
}
