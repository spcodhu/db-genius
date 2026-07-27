package com.dbgenius.agent.tool.file;

import com.alibaba.excel.support.ExcelTypeEnum;

/**
 * CSV 解析器：复用 ExcelParser 的表格解析逻辑（首行作表头），仅指定文件类型为 CSV。
 */
public class CsvParser extends ExcelParser {

    public CsvParser() {
        super(ExcelTypeEnum.CSV);
    }
}
