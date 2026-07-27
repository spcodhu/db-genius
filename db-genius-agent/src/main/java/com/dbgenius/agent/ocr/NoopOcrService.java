package com.dbgenius.agent.ocr;

/**
 * OCR 未启用（或凭证未配置）时的占位实现，返回提示文本供模型向用户解释。
 */
public class NoopOcrService implements OcrService {

    private final String reason;

    public NoopOcrService(String reason) {
        this.reason = reason;
    }

    @Override
    public String recognize(byte[] imageBytes) {
        return "OCR 功能未启用（" + reason + "），无法识别图片中的文字。";
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
