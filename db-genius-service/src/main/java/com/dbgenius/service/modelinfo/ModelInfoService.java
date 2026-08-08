package com.dbgenius.service.modelinfo;

import com.dbgenius.model.vo.ContextWindowLookupVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 模型上下文窗口解析服务。
 *
 * <p>解析顺序：① 内置注册表命中直接返回；② 未命中则远程探测 {@code GET {baseUrl}/v1/models}
 * 验证模型存在性（OpenAI 兼容标准上该接口不返回上下文大小），随后返回 not_found
 * 语义结果引导用户手填。探测失败不阻断流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelInfoService {

    private final KnownModelRegistry registry;
    private final RestClient.Builder restClientBuilder;

    public ContextWindowLookupVO lookup(String baseUrl, String apiKey, String modelName) {
        return registry.lookup(modelName)
                .map(window -> ContextWindowLookupVO.builder()
                        .modelName(modelName)
                        .contextWindow(window)
                        .source("registry")
                        .build())
                .orElseGet(() -> {
                    probeModelsEndpoint(baseUrl, apiKey);
                    return ContextWindowLookupVO.builder()
                            .modelName(modelName)
                            .contextWindow(null)
                            .source("not_found")
                            .build();
                });
    }

    /**
     * 仅验证连通性/模型存在；不解析上下文大小（标准接口不提供）。
     * 失败静默降级为 not_found，不打断用户保存配置。apiKey 不入日志。
     */
    private void probeModelsEndpoint(String baseUrl, String apiKey) {
        try {
            String url = buildModelsUrl(baseUrl);
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(Duration.ofSeconds(5));
            String body = restClientBuilder.clone()
                    .requestFactory(factory)
                    .build()
                    .get()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(String.class);
            log.debug("[ModelInfo] models endpoint reachable: {}, length={}", url,
                    body != null ? body.length() : 0);
        } catch (Exception e) {
            log.debug("[ModelInfo] models probe failed for baseUrl={}: {}", baseUrl, e.getMessage());
        }
    }

    /** baseUrl 已含 /v1 时拼 /models，否则拼 /v1/models */
    private String buildModelsUrl(String baseUrl) {
        String url = baseUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.endsWith("/v1") ? url + "/models" : url + "/v1/models";
    }
}
