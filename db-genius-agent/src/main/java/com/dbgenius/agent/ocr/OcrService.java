package com.dbgenius.agent.ocr;

/**
 * 图片文字识别服务。识别结果以纯文本返回；服务不可用时返回提示文本，
 * 识别失败抛异常由调用方（tool）转换为结构化错误文本。
 */
public interface OcrService {

    /**
     * 识别图片中的文字。
     *
     * @param imageBytes 图片二进制内容
     * @return 识别出的纯文本；OCR 未启用时返回未启用提示文本
     */
    String recognize(byte[] imageBytes);

    /**
     * OCR 是否真正可用。{@code false}（未启用/凭证缺失）时 tool 应以 success=false
     * 返回提示文本，避免模型把占位提示误判为识别结果。
     */
    default boolean isEnabled() {
        return true;
    }
}
