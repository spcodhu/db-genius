package com.dbgenius.agent.ocr;

import com.dbgenius.service.config.OssProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * OCR 服务装配：{@code db-genius.ocr.enabled=true} 且阿里云 AccessKey 已配置时注册
 * {@link AliyunOcrService}，否则注册 {@link NoopOcrService}。凭证复用 {@code db-genius.oss} 配置。
 */
@Slf4j
@Configuration
public class OcrConfig {

    @Bean
    public OcrService ocrService(OssProperties ossProperties,
                                 @Value("${db-genius.ocr.enabled:false}") boolean ocrEnabled,
                                 @Value("${db-genius.ocr.endpoint:ocr-api.cn-hangzhou.aliyuncs.com}") String ocrEndpoint)
            throws Exception {
        if (!ocrEnabled) {
            return new NoopOcrService("db-genius.ocr.enabled=false");
        }
        if (!StringUtils.hasText(ossProperties.getAccessKeyId())
                || !StringUtils.hasText(ossProperties.getAccessKeySecret())) {
            log.warn("OCR 已启用但阿里云 AccessKey 未配置，OCR 不可用");
            return new NoopOcrService("阿里云 AccessKey 未配置");
        }
        log.info("OCR 已启用, endpoint={}", ocrEndpoint);
        return new AliyunOcrService(ossProperties.getAccessKeyId(), ossProperties.getAccessKeySecret(), ocrEndpoint);
    }
}
