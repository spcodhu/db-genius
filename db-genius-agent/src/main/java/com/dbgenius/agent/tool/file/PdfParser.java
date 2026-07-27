package com.dbgenius.agent.tool.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.Map;

/**
 * PDF 解析器：PDFBox 抽取纯文本，最多抽取前 {@value #MAX_PAGES} 页。
 * 抽不到文字时提示"可能是扫描件，可转图片用 readImage"，由模型自行决策，不自动级联。
 */
public class PdfParser implements DocumentParser {

    /** 最多抽取的页数 */
    private static final int MAX_PAGES = 50;

    @Override
    public Map<String, Object> parse(InputStream in) throws Exception {
        // PDFBox 3.0.3 的 Loader 无 loadPDF(InputStream) 重载，读入字节再加载；
        // 上传白名单已把文件限制在 20MB 内，内存上界可控
        try (PDDocument document = Loader.loadPDF(in.readAllBytes())) {
            int totalPages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(totalPages, MAX_PAGES));
            String text = stripper.getText(document);

            Map<String, Object> result = TextContent.of(text);
            result.put("totalPages", totalPages);
            if (totalPages > MAX_PAGES) {
                result.put("truncated", true);
                result.put("message", "Only first " + MAX_PAGES + " pages extracted. Total pages: " + totalPages);
            }
            if (text.isBlank()) {
                result.put("message", "未能从该 PDF 抽取到文字，可能是扫描件；可将其转为图片后用 readImage 工具识别");
            }
            return result;
        }
    }
}
