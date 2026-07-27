package com.dbgenius.agent.ocr;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAdvancedRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAdvancedResponse;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;

/**
 * 阿里云 OCR（RecognizeAdvanced 通用文字识别）实现，凭证复用 OSS 的 RAM AccessKey。
 */
@Slf4j
public class AliyunOcrService implements OcrService {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Client client;

    public AliyunOcrService(String accessKeyId, String accessKeySecret, String endpoint) throws Exception {
        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret);
        config.endpoint = endpoint;
        this.client = new Client(config);
    }

    @Override
    public String recognize(byte[] imageBytes) {
        try {
            RecognizeAdvancedRequest request = new RecognizeAdvancedRequest()
                    .setBody(new ByteArrayInputStream(imageBytes));
            RecognizeAdvancedResponse response = client.recognizeAdvanced(request);
            String data = response.getBody().getData();
            if (data == null || data.isBlank()) {
                return "";
            }
            // data 为 JSON 字符串，content 字段是整图文字；缺失时退回原始 JSON
            JsonNode content = objectMapper.readTree(data).get("content");
            return content != null ? content.asText() : data;
        } catch (Exception e) {
            log.error("Aliyun OCR recognize failed", e);
            throw new IllegalStateException("OCR 识别失败: " + e.getMessage(), e);
        }
    }
}
