package com.dbgenius.agent.tool;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelParseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "Parse an Excel file and return its content as JSON. The file path must be an absolute path to an .xlsx or .xls file.")
    public String parseExcel(
            @ToolParam(description = "Absolute path to the Excel file") String filePath) {
        log.info("Parsing Excel file: {}", filePath);

        try {
            List<Map<String, Object>> allRows = new ArrayList<>();
            List<String> headers = new ArrayList<>();

            EasyExcel.read(filePath, new AnalysisEventListener<Map<Integer, String>>() {
                @Override
                public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                    headers.clear();
                    int maxIndex = headMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                    for (int i = 0; i <= maxIndex; i++) {
                        headers.add(headMap.getOrDefault(i, "column_" + i));
                    }
                }

                @Override
                public void invoke(Map<Integer, String> data, AnalysisContext context) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < headers.size(); i++) {
                        row.put(headers.get(i), data.getOrDefault(i, null));
                    }
                    allRows.add(row);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    log.info("Excel parsing complete, {} rows read", allRows.size());
                }
            }).sheet().doRead();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("fileName", filePath);
            result.put("headers", headers);
            result.put("totalRows", allRows.size());
            result.put("data", allRows.size() > 200 ? allRows.subList(0, 200) : allRows);
            if (allRows.size() > 200) {
                result.put("truncated", true);
                result.put("message", "Only first 200 rows returned. Total: " + allRows.size());
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("Excel parsing failed", e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", "Failed to parse Excel: " + e.getMessage());
            try {
                return objectMapper.writeValueAsString(result);
            } catch (Exception ex) {
                return "Error: " + e.getMessage();
            }
        }
    }
}
