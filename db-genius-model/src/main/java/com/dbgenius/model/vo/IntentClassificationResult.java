package com.dbgenius.model.vo;

import com.dbgenius.model.enums.IntentType;

/**
 * 意图分类结果
 *
 * @param intent             识别到的意图
 * @param confidence         置信度 0.0-1.0
 * @param reasoning          判断依据
 * @param needsClarification 是否需要用户确认
 */
public record IntentClassificationResult(
        IntentType intent,
        double confidence,
        String reasoning,
        boolean needsClarification
) {
}
