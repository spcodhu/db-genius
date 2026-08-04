package com.dbgenius.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.mapper.ModelProviderMapper;
import com.dbgenius.model.entity.ModelProvider;
import com.dbgenius.model.enums.ModelProviderType;
import com.dbgenius.model.vo.ModelProviderVO;
import com.dbgenius.service.ModelProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模型提供商预设服务实现。
 *
 * <p>系统启动时通过 {@link #initBuiltinProviders()} 自动检查并插入内置预设，
 * 保障新部署实例开箱即用。调用方通过 {@link PostConstruct} 或
 * {@code ApplicationRunner} 触发。
 */
@Slf4j
@Service
public class ModelProviderServiceImpl extends ServiceImpl<ModelProviderMapper, ModelProvider>
        implements ModelProviderService {

    @Override
    public List<ModelProviderVO> listProviders() {
        return list().stream()
                .sorted(Comparator.comparing(ModelProvider::getSortOrder))
                .map(this::toVO)
                .toList();
    }

    @Override
    public void initBuiltinProviders() {
        long count = count();
        if (count > 0) {
            log.info("ModelProvider presets already exist ({} records), skip init.", count);
            return;
        }

        log.info("Initializing built-in ModelProvider presets...");
        List<ModelProvider> builtins = new ArrayList<>();

        builtins.add(buildProvider("deepseek", "DeepSeek",
                "https://api.deepseek.com", "deepseek-v4-pro", 10));
        builtins.add(buildProvider("openai", "OpenAI",
                "https://api.openai.com", "gpt-4o", 20));
        builtins.add(buildProvider("ollama", "Ollama",
                "http://localhost:11434", "llama3.1", 60));

        // 自定义 OpenAI 兼容：无预设 baseUrl/model，让用户自行填写
        ModelProvider custom = new ModelProvider();
        custom.setProviderCode("custom");
        custom.setDisplayName("自定义（OpenAI 兼容）");
        custom.setProviderType(ModelProviderType.OPENAI_COMPATIBLE);
        custom.setBuiltin(true);
        custom.setSortOrder(90);
        builtins.add(custom);

        saveBatch(builtins);
        log.info("Built-in ModelProvider presets initialized: {} providers.", builtins.size());
    }

    private ModelProvider buildProvider(String code, String name, String baseUrl, String model, int sortOrder) {
        ModelProvider provider = new ModelProvider();
        provider.setProviderCode(code);
        provider.setDisplayName(name);
        provider.setProviderType(ModelProviderType.OPENAI_COMPATIBLE);
        provider.setDefaultBaseUrl(baseUrl);
        provider.setDefaultModel(model);
        provider.setBuiltin(true);
        provider.setSortOrder(sortOrder);
        return provider;
    }

    private ModelProviderVO toVO(ModelProvider entity) {
        ModelProviderVO vo = new ModelProviderVO();
        vo.setProviderCode(entity.getProviderCode());
        vo.setDisplayName(entity.getDisplayName());
        vo.setProviderType(entity.getProviderType().getCode());
        vo.setDefaultBaseUrl(entity.getDefaultBaseUrl());
        vo.setDefaultModel(entity.getDefaultModel());
        vo.setBuiltin(entity.getBuiltin());
        vo.setSortOrder(entity.getSortOrder());
        return vo;
    }
}
