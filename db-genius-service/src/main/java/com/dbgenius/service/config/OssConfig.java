package com.dbgenius.service.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 构建阿里云 OSS client，配置缺失时启动即失败（fail-fast）。
 */
@Configuration
@RequiredArgsConstructor
public class OssConfig {

    private final OssProperties ossProperties;

    @PostConstruct
    public void validate() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(ossProperties.getEndpoint())) {
            missing.add("endpoint (env ALIYUN_OSS_ENDPOINT)");
        }
        if (!StringUtils.hasText(ossProperties.getBucket())) {
            missing.add("bucket (env ALIYUN_OSS_BUCKET)");
        }
        if (!StringUtils.hasText(ossProperties.getAccessKeyId())) {
            missing.add("access-key-id (env ALIYUN_ACCESS_KEY_ID)");
        }
        if (!StringUtils.hasText(ossProperties.getAccessKeySecret())) {
            missing.add("access-key-secret (env ALIYUN_ACCESS_KEY_SECRET)");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Aliyun OSS is not configured, missing: " + String.join(", ", missing));
        }
    }

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient() {
        return new OSSClientBuilder().build(
                ossProperties.getEndpoint(),
                ossProperties.getAccessKeyId(),
                ossProperties.getAccessKeySecret());
    }
}
