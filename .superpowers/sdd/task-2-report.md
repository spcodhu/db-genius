# Task 2 报告 — Phase 2+3：通用文档读取 tool + 图片 OCR tool

日期：2026-07-27
计划来源：`docs/plan/oss-storage-and-file-tools-plan.md` §4 Phase 2 / Phase 3 / §5
约束遵守：全程无任何 git 操作；未触碰 `WorkflowHandler.java` 与 `DbWorkflowAgent.java`（Task 3 范围）。
说明：本任务由两个 subagent 接力完成，本报告为收尾校验 + 验证汇总。前一棒产出**完整可用，无需修补任何中断残留**（逐文件 Read 核对 + 编译验证确认）。

## 文件清单与职责

### 新增（db-genius-agent）

| 文件 | 职责 |
|---|---|
| `tool/FileReadTool.java` | 通用文档读取 tool（xlsx/xls/csv/docx/pdf/md），按扩展名分发 parser，统一返回 JSON `{success, fileName, format, ...}`，try-with-resources 关闭 `openStream` |
| `tool/ImageReadTool.java` | 图片 OCR tool（png/jpg/jpeg/webp/bmp），10MB 上限，OCR 文本 30k 字符截断（`truncated` 标注） |
| `tool/file/FileAccessGuard.java` | 两 tool 共用的访问守卫：ToolContext 身份/白名单校验 → fileId 包含性检查 → `getOwnedFile` 属主二次校验；一切拒绝返回结构化 JSON（`{success:false, error}`），不抛异常 |
| `tool/file/DocumentParser.java` | parser SPI：`Map<String, Object> parse(InputStream)`，流由调用方关闭 |
| `tool/file/TextContent.java` | 纯文本截断封装（30k 字符上限，`truncated`/`totalChars` 标注），docx/pdf/md 共用 |
| `tool/file/ExcelParser.java` | xlsx/xls：EasyExcel 读 InputStream，200 行截断，输出 headers/totalRows/data（逻辑迁移自已删的 ExcelParseTool） |
| `tool/file/CsvParser.java` | csv：继承 ExcelParser，指定 `ExcelTypeEnum.CSV`，输出同构 |
| `tool/file/DocxParser.java` | docx：POI `XWPFWordExtractor` 抽全文（.doc 老格式不支持，上传白名单本就不含） |
| `tool/file/PdfParser.java` | pdf：PDFBox 3.x `Loader.loadPDF`，限 50 页；抽不到文字时提示"可能是扫描件，可转图片用 readImage" |
| `tool/file/MarkdownParser.java` | md：UTF-8 纯文本读取 |
| `ocr/OcrService.java` | OCR SPI：`String recognize(byte[] imageBytes)` |
| `ocr/AliyunOcrService.java` | 阿里云官方 SDK 实现：RecognizeAdvanced 通用文字识别，返回 data JSON 的 `content` 字段（缺失时退回原始 JSON） |
| `ocr/NoopOcrService.java` | OCR 未启用/凭证未配置时的占位实现，返回提示文本 |
| `ocr/OcrConfig.java` | 装配：`db-genius.ocr.enabled=true` 且 AccessKey 已配置 → Aliyun 实现，否则 Noop；凭证复用 `db-genius.oss.access-key-id/secret`（经 `OssProperties`）；endpoint 默认 `ocr.cn-hangzhou.aliyuncs.com`（可经 `db-genius.ocr.endpoint` 覆盖） |

### 删除

- `tool/ExcelParseTool.java` — 能力由 `FileReadTool` + `ExcelParser` 覆盖。

### 修改

- 根 `pom.xml`：properties 新增 `aliyun-ocr.version=3.1.3`；`dependencyManagement` 新增 `com.aliyun:ocr_api20210707:${aliyun-ocr.version}`（版本统一 pin 在根 pom）。
- `db-genius-agent/pom.xml`：新增 `com.aliyun:ocr_api20210707` 依赖（pdfbox/poi-ooxml 为 Task 1 已加）。

## 四个核心类最终签名

```java
// com.dbgenius.agent.tool.FileReadTool
@Tool(description = "Read an uploaded document (xlsx/xls/csv/docx/pdf/md) and return its content as structured JSON. "
        + "fileId is the number from a [file#N: name] reference in the conversation.")
public String readFile(@ToolParam(description = "The file ID, e.g. 12") Long fileId,
                       ToolContext toolContext)   // Spring AI 自动排除出 LLM JSON Schema

// com.dbgenius.agent.tool.ImageReadTool
@Tool(description = "Recognize text in an uploaded image (png/jpg/jpeg/webp/bmp) via OCR. "
        + "fileId is the number from a [file#N: name] reference in the conversation.")
public String readImage(@ToolParam(description = "The file ID, e.g. 12") Long fileId,
                        ToolContext toolContext)

// com.dbgenius.agent.tool.file.FileAccessGuard
public GuardResult check(Long fileId, ToolContext toolContext);
public record GuardResult(UploadedFile file, String errorJson) { boolean ok(); }
// ToolContext key 常量：CONTEXT_USER_ID="userId"、CONTEXT_ALLOWED_FILE_IDS="allowedFileIds"

// com.dbgenius.agent.ocr.OcrService
String recognize(byte[] imageBytes);
```

`ToolContext` 仅作为方法参数类型出现，无 `@ToolParam`，不出现在 LLM 可见参数描述中；从 context 取 `"userId"`（Number→Long 兼容转换）与 `"allowedFileIds"`（`Collection<?>`，元素按 Number 比较）。

## OCR SDK 版本

- `com.aliyun:ocr_api20210707:3.1.3`（根 pom `aliyun-ocr.version` property + dependencyManagement pin）。
- 编译期已实际下载并解析成功（`AliyunOcrService` 引用其 `Client`/`RecognizeAdvancedRequest` 编译零错误）。
- 与计划 Phase 0 第 1 条的备选分支一致（Hutool 直调 POP API 签名过繁，采用官方 SDK，代码量更小）。

## 编译验证

环境：`export JAVA_HOME="D:\Java\java21"`（git-bash，Windows）。

命令：`./mvnw clean compile -DskipTests -pl db-genius-agent -am`

结果：common/model/service **SUCCESS**，agent **FAILURE**，共 5 个编译错误，全部为 `找不到符号：类 ExcelParseTool`（控制台中文乱码为 GBK 显示问题，不影响判断），错误文件清单（grep `[ERROR].*\.java:\[` 去重）：

```
db-genius-agent/src/main/java/com/dbgenius/agent/DbWorkflowAgent.java:[3,31]
db-genius-agent/src/main/java/com/dbgenius/agent/DbWorkflowAgent.java:[18,28]
db-genius-agent/src/main/java/com/dbgenius/agent/intent/WorkflowHandler.java:[4,31]
db-genius-agent/src/main/java/com/dbgenius/agent/intent/WorkflowHandler.java:[45,19]
db-genius-agent/src/main/java/com/dbgenius/agent/intent/WorkflowHandler.java:[37,1]
```

**仅涉及 `DbWorkflowAgent.java` 与 `WorkflowHandler.java` 两个文件（Task 3 范围），其余 30 个源文件零错误** —— 符合验收预期。注：`WorkflowHandler.java:74` 的 `getFilePath` 引用错误本轮未单独报出（javac 在符号缺失后跳过后续归因），Task 3 接线时会一并消除。

## 验收点逐条核对

1. ✅ 签名与 ToolContext 用法符合（见上）；context 取 `userId`/`allowedFileIds`。
2. ✅ 校验链完整：context 缺失/类型错误 → 拒绝；fileId ∉ allowedFileIds → 拒绝；`getOwnedFile` 二次属主校验（`BusinessException` 捕获转拒绝文本）；全部返回结构化 JSON，无异常抛出。
3. ✅ xlsx/xls EasyExcel 流式 + 200 行截断；csv 同构；docx XWPFWordExtractor；pdf PDFBox 3.x `Loader.loadPDF` 限 50 页 + 扫描件提示转 readImage；md UTF-8；文本类经 `TextContent` 30k 截断且 `truncated: true`。
4. ✅ ImageReadTool：`FileTypes.isImage` 白名单、`readNBytes(10MB+1)` 上限校验、OCR 文本 30k 截断。
5. ✅ `FileReadTool:71`、`ImageReadTool:60` 均 try-with-resources 关闭 `openStream`；parser 内部资源（XWPFDocument/PDDocument）亦 try-with-resources。
6. ✅ OcrService 接口 + Aliyun（RecognizeAdvanced，凭证复用 `db-genius.oss` AccessKey）+ Noop（enabled=false 提示）+ 版本 pin 根 pom。
7. ✅ `WorkflowHandler.java` 与 `DbWorkflowAgent.java` 未做任何改动。

## 修补了哪些中断残留

无。前一棒被中断时所有文件已写完且内容完整（逐文件 Read 核对），pom 依赖齐备，一次编译即达到预期错误形态。本棒仅做了校验、编译验证与本报告。

## 坑

- **javac 中文错误消息在 git-bash 控制台乱码**（GBK 控制台），错误位置与符号名仍清晰可读，不影响判断。
- **`com.aliyun:ocr_api20210707` 坐标易与旧版 `ocr20191230` 等混淆**：确认用 2021-07-07 版 API 包名 `com.aliyun.ocr_api20210707`，`RecognizeAdvancedRequest.setBody(InputStream)` 直接传图片字节流，无需先传 URL。
- **`RecognizeAdvanced` 返回的 `body.data` 是 JSON 字符串而非纯文本**，需二次解析取 `content` 字段；已在 `AliyunOcrService` 处理并留原始 JSON 兜底。
- **javac 报错不完整**：首个符号缺失（import ExcelParseTool）后，同文件后续的 `getFilePath` 引用错误不再报出。Task 3 修这两个文件时不要只修 import，要整体按 Phase 4 改造。

## 遗留问题（交后续任务）

- `DbWorkflowAgent.java` / `WorkflowHandler.java` 编译错误 —— 预期，Task 3（Phase 4 接线：toolContext 透传 + fileId 逻辑引用 + 注册新 tools）。
- 计划 §6 要求的 `FileReadToolTest` 单测未写（本轮验收以编译为准；如需补测，mock FileUploadService + 构造 ToolContext 即可，OcrService 可 mock 或用 Noop）。
- `db-genius.ocr.endpoint` 配置项仅在 `OcrConfig` 的 `@Value` 默认值中存在，`application.yml` / `.env.example` 未列出该可选项（有默认值不影响运行，Phase 5 文档收尾时可补注）。
- Task 1 遗留仍有效：`docker-compose.yml` 的 `DB_GENIUS_FILE_DIR`、`README.md` / `api-docs.yaml` 旧字段，属 Phase 5。


---

# 修复轮次 1

日期：2026-07-27（评审反馈修复，无任何 git 操作）

## 修复 1（Important）— parser 内存风险

- **`tool/file/ExcelParser.java`**：原为全量行读入内存再截断。改为 EasyExcel 惯用的监听器内计数：`invoke` 中先 `totalRows++`，行数收集满 200 行后直接 `return` 不再构建行 Map（内存上界恒定 = 200 行），但**继续读完整个流**。
  - **取舍说明**：选择"标记位跳过收集 + 继续扫描"，而非抛异常中断读取。因此 `totalRows` 是**精确总行数**（非"≥200"语义），对模型判断数据规模更有价值；代价是 CPU 需扫完整个文件——上传白名单已把文件限制在 20MB 内，可接受。监听器实例以 `var` 持有（`var` 捕获匿名类自身类型，故可读取其包级字段 `totalRows`）。
  - **`CsvParser.java`**：继承 ExcelParser，同一问题随之修复，无需单独改动。
- **`tool/file/MarkdownParser.java`**：`readAllBytes()` 改为 `readNBytes(BYTE_LIMIT)`，`BYTE_LIMIT = MAX_CHARS * 4 + 1`（UTF-8 单字符最多 4 字节，读满上限即可保证解码后超 30k 字符，截断判定仍由 `TextContent.of` 统一完成；不足上限即全量）。内存上界 ≈ 120KB，不再整文件入内存。
- **DocxParser / PdfParser**：存在同类"整文档入内存"特性（POI `XWPFDocument` 与 PDFBox `Loader.loadPDF` 均需加载完整文档结构，无简单的限量流式手段），**按计划不修**；风险由上传 20MB 白名单上限兜住。

## 修复 2（Minor-1）— 异常逃逸

- **`tool/FileReadTool.java` / `tool/ImageReadTool.java`**：`guard.check`（内含 `getOwnedFile` 的 DB 访问）原先在 try 块外，非预期异常（DB 异常等）会逃出 tool 打断 ReAct 循环。现将**守卫校验 + 下载 + 解析/OCR + JSON 序列化全链路**包进单一 try-catch，任何 `Exception` 都 log 后转为 `{success:false, error}` 结构化文本返回。原内层 catch 删除（由外层统一兜底），try-with-resources 关闭 `openStream` 的行为不变。

## 修复 3（Minor-5）— NoopOcrService 语义

- **`ocr/OcrService.java`**：接口新增 `default boolean isEnabled() { return true; }`。
- **`ocr/NoopOcrService.java`**：覆写 `isEnabled()` 返回 `false`。
- **`ImageReadTool`**：扩展名校验后先判 `ocrService.isEnabled()`，为 false 时直接返回 `{success:false, error:"OCR 功能未启用（…）…"}`（提示文本取自 Noop 的 `recognize`，与入参无关），**不再以 success:true 返回占位提示**，避免模型误判 OCR 成功；且在打开 OSS 流之前短路，省一次无用下载。

## 编译验证（修复后）

`export JAVA_HOME="D:\Java\java21"` + `./mvnw clean compile -DskipTests -pl db-genius-agent -am`

结果与修复前完全一致：common/model/service SUCCESS，agent FAILURE，共 5 个 `找不到符号：类 ExcelParseTool` 错误，文件清单（grep `[ERROR].*\.java:\[` 去重）：

```
db-genius-agent/src/main/java/com/dbgenius/agent/DbWorkflowAgent.java:[3,31]
db-genius-agent/src/main/java/com/dbgenius/agent/DbWorkflowAgent.java:[18,28]
db-genius-agent/src/main/java/com/dbgenius/agent/intent/WorkflowHandler.java:[4,31]
db-genius-agent/src/main/java/com/dbgenius/agent/intent/WorkflowHandler.java:[45,19]
db-genius-agent/src/main/java/com/dbgenius/agent/intent/WorkflowHandler.java:[37,1]
```

仅 `DbWorkflowAgent.java` 与 `WorkflowHandler.java`（Task 3 范围），本轮修改的 6 个文件及其余源文件零错误。

## 接口签名变更（相对初版报告）

```java
// com.dbgenius.agent.ocr.OcrService — 新增一个 default 方法
String recognize(byte[] imageBytes);
default boolean isEnabled() { return true; }   // Noop 覆写为 false
```

其余类签名不变。Task 3 接线时注意：`readImage` 在 OCR 未启用时返回 success:false 的 JSON 错误文本，属正常提示路径，无需特殊处理。
