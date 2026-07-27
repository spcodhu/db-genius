package com.dbgenius.agent.tool.file;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.InputStream;
import java.util.Map;

/**
 * Word（docx）解析器：POI XWPFWordExtractor 抽取纯文本。
 */
public class DocxParser implements DocumentParser {

    @Override
    public Map<String, Object> parse(InputStream in) throws Exception {
        try (XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return TextContent.of(extractor.getText());
        }
    }
}
