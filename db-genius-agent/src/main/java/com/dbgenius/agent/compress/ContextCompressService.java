package com.dbgenius.agent.compress;

import com.dbgenius.model.vo.CompressResultVO;

import java.util.Locale;

/**
 * 上下文压缩编排服务：属主校验 + 按策略编码分派到 {@link ContextCompressor}。
 */
public interface ContextCompressService {

    /**
     * 主动压缩指定会话的上下文。
     *
     * @param userId         当前用户（属主校验，非本人会话抛 404）
     * @param conversationId 会话 ID
     * @param options        压缩选项，可为 null
     * @param locale         本次压缩的语言环境（同步链路取 LocaleContextHolder，异步链路显式传入）
     */
    CompressResultVO compress(Long userId, Long conversationId, CompressOptions options, Locale locale);
}
