package com.dbgenius.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 模型提供商协议类型。
 *
 * <p>OpenAI 兼容协议覆盖 DeepSeek、OpenAI、Ollama、vLLM 等绝大多数厂商。
 * Anthropic（Claude）使用非兼容协议，预留后续扩展。
 */
@Getter
@AllArgsConstructor
public enum ModelProviderType {

    OPENAI_COMPATIBLE("openai_compatible", "OpenAI 兼容协议");

    @EnumValue
    private final String code;
    private final String desc;
}
