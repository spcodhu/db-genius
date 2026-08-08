package com.dbgenius.agent.compress;

/**
 * 上下文压缩选项。
 *
 * @param targetTokens 期望压缩到的目标 token 数，可为 null（由策略自行决定）
 */
public record CompressOptions(Integer targetTokens) {

    public static CompressOptions of(Integer targetTokens) {
        return new CompressOptions(targetTokens);
    }
}
