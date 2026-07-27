package com.dbgenius.agent.tool.file;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.support.ExcelTypeEnum;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 解析器（xlsx/xls），逻辑迁移自已删除的 ExcelParseTool，改为读 {@link InputStream}。
 * 输出结构沿用原风格：headers/totalRows/data/truncated/message，超过 {@value #MAX_ROWS} 行截断。
 *
 * <p>内存防护：收集满 {@value #MAX_ROWS} 行后不再收集行数据（内存上界恒定），
 * 但继续读完整个流以累计精确的总行数 totalRows——文件经上传白名单 20MB 上限约束，
 * 读完的 CPU 代价可接受，精确总数对模型判断数据规模更有价值。</p>
 */
@Slf4j
public class ExcelParser implements DocumentParser {

    /** 返回给模型的最大行数 */
    private static final int MAX_ROWS = 200;

    private final ExcelTypeEnum excelType;

    public ExcelParser() {
        this(null);
    }

    protected ExcelParser(ExcelTypeEnum excelType) {
        this.excelType = excelType;
    }

    @Override
    public Map<String, Object> parse(InputStream in) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        /** 监听器持有总行数计数：收集满 {@value #MAX_ROWS} 行后只计数不收集，内存上界恒定 */
        var listener = new AnalysisEventListener<Map<Integer, String>>() {

            /** 已扫描的总行数（含未收集的），包级可读供 parse 取回 */
            int totalRows;

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
                totalRows++;
                if (rows.size() >= MAX_ROWS) {
                    return;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), data.getOrDefault(i, null));
                }
                rows.add(row);
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.info("Tabular file parsing complete, {} rows scanned, {} rows collected", totalRows, rows.size());
            }
        };
        var readerBuilder = EasyExcel.read(in, listener);
        if (excelType != null) {
            readerBuilder.excelType(excelType);
        }
        readerBuilder.sheet().doRead();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("headers", headers);
        result.put("totalRows", listener.totalRows);
        result.put("data", rows);
        if (listener.totalRows > MAX_ROWS) {
            result.put("truncated", true);
            result.put("message", "Only first " + MAX_ROWS + " rows returned. Total: " + listener.totalRows);
        }
        return result;
    }
}
