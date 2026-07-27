# Task 2 评审 — 通用文档读取 tool + 图片 OCR tool

日期：2026-07-27
评审范围：`docs/plan/oss-storage-and-file-tools-plan.md` §4 Phase 2/3、§5 对照实现；代码质量（逻辑缺陷/NPE/资源泄漏/大文件内存/库 API 正确性）。
评审方式：只读。逐文件 Read 全部新增代码 + `git status` 核对改动边界 + 独立编译复验 + easyexcel-core 4.0.3 字节码级验证 xls 支持。

## 一、规格符合性（绑定约束逐条）

| # | 约束 | 结论 | 证据 |
|---|---|---|---|
| 1 | 签名为 `(Long fileId, ToolContext)`，ToolContext 为 `org.springframework.ai.chat.model.ToolContext`；LLM 可见描述无路径/userId/OSS key | ✅ | `FileReadTool.java:42-44`、`ImageReadTool.java:44-46`；@Tool/@ToolParam 文案仅提 fileId 与 `[file#N: name]` 逻辑引用 |
| 2 | context key `"userId"`/`"allowedFileIds"`；缺失/类型错 → 拒绝文本 | ✅ | `FileAccessGuard.java:29-32`（常量）、`44-50`（`instanceof Number` / `instanceof Collection` 双检，任一不满足即拒绝） |
| 3 | 校验链 = allowedFileIds 包含性 + `getOwnedFile` 属主二次校验；拒绝一律结构化 JSON，不抛异常 | ✅（带 Minor ①） | `FileAccessGuard.java:53-69`：白名单按 Number 比较 → `getOwnedFile` 的 `BusinessException` 捕获转 deny JSON |
| 4 | FileReadTool 各格式与截断规则 | ✅ | xlsx/xls/csv `ExcelParser.java`（EasyExcel 读 InputStream，200 行截断 + totalRows/truncated/message）；docx `DocxParser.java`（XWPFWordExtractor）；pdf `PdfParser.java`（PDFBox 3.x `Loader.loadPDF`，50 页 + 扫描件提示转 readImage）；md `MarkdownParser.java`（UTF-8）；文本类经 `TextContent.java` 30k 截断 + `truncated`/`totalChars`；不支持扩展名返回错误文本（`FileReadTool.java:62-65`） |
| 5 | ImageReadTool：IMAGE_EXTENSIONS 白名单、10MB 上限、30k 截断 | ✅ | `ImageReadTool.java:55`（`FileTypes.isImage`）、`61-64`（`readNBytes(10MB+1)` 超限拒绝）、`70-76`（30k 截断 + truncated） |
| 6 | 所有 OSS InputStream try-with-resources | ✅ | `FileReadTool.java:71`、`ImageReadTool.java:60` 包住 `openStream`；parser 内部资源 `DocxParser.java:16-17`（XWPFDocument+extractor）、`PdfParser.java:21`（PDDocument）均 try-with-resources；Excel/Csv/Markdown parser 不持有额外资源，流契约"调用方关闭"由 `DocumentParser` javadoc 明确 |
| 7 | OCR 官方 SDK + 版本 pin 根 pom + 凭证复用 oss access-key + enabled=false 时 Noop + 无手写签名 | ✅ | 根 pom `aliyun-ocr.version=3.1.3` + dependencyManagement（task-2-diff.txt:170-175），本地仓库已实际解析 3.1.3 jar；`OcrConfig.java:23-32`（enabled/凭证双分支 → Noop）；`AliyunOcrService.java` 用 SDK `Config.setAccessKeyId/Secret`，无手写签名 |
| 8 | `ExcelParseTool.java` 已删；`WorkflowHandler.java`、`DbWorkflowAgent.java` 未被本任务修改 | ✅ | `git status`：两文件不在 modified/untracked 列表；ExcelParseTool 状态为 `D` |
| 9 | 无计划外产物 | ✅ | 本任务新增仅 `ocr/`(4)、`tool/file/`(8)、`FileReadTool`、`ImageReadTool`，与实现者报告清单一一对应；其余改动文件均属 Task 1 |

**规格结论：✅ 全部通过。** 两处与计划字面有出入但属合理偏差，见"待确认项"2、3。

### xls 支持的专项验证（评审主动追加）

EasyExcel 3.x 曾移除 xls 支持，而实现未显式 `excelType`（xls 走自动识别），存在规格落空风险。对本地仓库 `easyexcel-core-4.0.3.jar` 做字节码级验证：`ExcelTypeEnum` 含 `XLS` 枚举值，`recognitionExcelType(InputStream)` 通过 POI `IOUtils.peekFirstNBytes` 做 OLE2 magic 识别并返回 XLS；`com/alibaba/excel/analysis/v03/**`（XlsListSheetListener 等全套 handler）与 `context/xls`、`metadata/holder/xls` 均存在。**xls 经 InputStream 自动识别可读，规格成立**（仍建议冒烟实测，见待确认项 3）。

## 二、代码质量

### Critical

无。

### Important

1. **表格/文本类解析整量入内存，截断在读取之后** — `ExcelParser.java:37,72` 把**全部行**累积进 `allRows` 再截断 200（沿用旧 ExcelParseTool 行为）；`MarkdownParser.java:14` `readAllBytes()` 整文件入内存再截断 30k。上传上限 20MB 封底下，一份稠密 CSV 可达数十万行，行对象（LinkedHashMap + String）膨胀 5–10 倍，单次调用数百 MB，并发场景有 OOM 风险。建议：Excel 侧收集到 200 行后停止入 list、仅继续计数（保留 `totalRows` 语义），或加行数硬上限；md 用 `readNBytes` 限量读。不阻断合入，建议 Task 3 或后续顺手修。

### Minor

1. **`guard.check` 在 try 之外，非 BusinessException 会逃出 tool** — `FileReadTool.java:47`、`ImageReadTool.java:49`。`FileAccessGuard` 只捕获 `BusinessException`（`FileAccessGuard.java:65`）；若 `getOwnedFile` 抛 DataAccessException 等基础设施异常，会逃出 tool 方法打断 ReAct 循环，与"tool 失败不抛异常"的设计意图有缝隙。建议 guard 兜底 `catch (Exception)`。
2. **10MB 上限未做读前预检** — `ImageReadTool.java:60-64`：`readNBytes(MAX+1)` 本身是限量流式读（至多消耗 10MB+1 字节即关流，未整坨读完，**无内存问题**），但 DB 实体有 `fileSize`，可在开流前直接拒绝 >10MB 文件，省一次 OSS 连接与 10MB 网络传输。
3. **PdfParser 两个 message 互相覆盖、扫描件判断只看前 50 页** — `PdfParser.java:30-36`：页数截断 message 会被 `text.isBlank()` 的扫描件提示覆盖；且 isBlank 仅基于前 50 页抽取结果，超长 PDF 可能误报"扫描件"。建议合并文案。
4. **两个 tool 成功 JSON 结构不完全对齐** — FileReadTool 有 `format`，ImageReadTool 没有；表格类输出 `headers/totalRows/data`、文本类 `content`、图片 `text`。沿用旧 ExcelParseTool 风格，LLM 可按 format/字段分支理解，可接受，记录备查。
5. **NoopOcrService 提示以 `success:true` + text 返回**（`ImageReadTool.java:65-76`）——语义上更像"能力未启用"而非识别成功；不影响安全，模型能读懂并转述，可考虑改 `success:false` 或独立字段。
6. **`db-genius.ocr.endpoint` 仅在 `OcrConfig` 的 `@Value` 默认值中存在**，`application.yml`/`.env.example` 未列（实现者报告已自述，Phase 5 收尾）。
7. **计划 §6 要求的 `FileReadToolTest` 未写**（实现者报告已自述并给出 mock 思路，建议 Task 3 接线后补）。

### 库 API 正确性核对

- PDFBox 3.0.3：`Loader.loadPDF(InputStream)` 为 3.x 正式 API，用法正确（整文件入内存，20MB 封底下可接受）；`PDFTextStripper.setStartPage/setEndPage` 用法正确。
- POI 5.2.5：`XWPFDocument(InputStream)` + `XWPFWordExtractor` 用法正确，双资源关闭顺序安全。
- EasyExcel 4.0.3：`EasyExcel.read(InputStream, listener)` + `excelType(ExcelTypeEnum.CSV)` 用法正确；无头类时默认首行为 head 触发 `invokeHeadMap`，xlsx/xls/csv 三路径一致。
- 阿里云 OCR SDK 3.1.3：`RecognizeAdvancedRequest.setBody(InputStream)` 直传字节流正确；`body.data` 为 JSON 字符串、二次解析取 `content`、缺失退回原始 JSON 的处理与 SDK 实际返回结构一致；endpoint `ocr.cn-hangzhou.aliyuncs.com` 为 SDK 版正确地址（计划文中的 `ocr-api.*` 是 POP HTTP 版，属计划 Phase 0 第 1 条明示的备选分支切换）。
- 输出风格：`{success, error}` JSON 与旧 ExcelParseTool 一致，优于 SqlExecuteTool 的裸 `"Error: ..."` 文本，风格无倒退。

### 编译独立复验

`export JAVA_HOME="D:\Java\java21" && ./mvnw clean compile -DskipTests -pl db-genius-agent -am`：common/model/service SUCCESS，agent FAILURE，错误恰好 5 个、全部是 `DbWorkflowAgent.java` 与 `WorkflowHandler.java` 中"找不到符号 ExcelParseTool"——与实现者报告完全一致，符合计划 §7 明示的 Phase 4 前中间态。本任务新增代码零编译错误。

**质量结论：需修复（轻量）** — 无 Critical；1 个 Important（解析整量入内存）建议尽快修；Minor ①（guard 非 BusinessException 逃逸）建议 Task 3 接线时一并修。均不构成阻断 Task 3 的缺陷。

## 三、⚠️ 待确认项

1. **agent 模块当前编译失败是计划内中间态**（计划 §7："Phase 4 之前应用处于中间态，不可中途发布"）——确认主线在 Task 3 完成前不发布/不合入可发布分支。
2. **`FileAccessGuard` 为 public @Component，非计划所述 package-private** —— 计划 Phase 3 第 2 条写"抽一个 package-private 的 FileAccessGuard"，但 FileReadTool/ImageReadTool 在 `agent.tool` 包、guard 在 `agent.tool.file` 包，跨包注入 package-private 不可行；public @Component 是必要且合理的偏差，请主 agent 知悉确认。
3. **xls/CSV 解析仅静态验证**（字节码级确认 EasyExcel 4.0.3 支持），未经真实 .xls/.csv 文件运行验证；CSV 首行表头行为同理。建议 Task 3 接线后按 §6 冒烟清单实测。
4. **FileReadTool 表格类输出字段为 `headers/totalRows/data`**，与计划 Phase 2 示例的 `{success, fileName, format, truncated, content}` 字面不同——沿用旧 ExcelParseTool 结构（对既有提示词/模型习惯更友好），视为合理偏差。
5. OCR 按次计费：trial 模式上传被禁无防刷问题（计划 §7 已述）；正式环境 RAM 子账号最小授权为部署侧注意事项，非本任务代码问题。

---

# 复审轮次 1

日期：2026-07-27
复审对象：实现者按初评完成的 3 项修复（修复说明见 `.superpowers/sdd/task-2-report.md` 末尾「修复轮次 1」）。方式：逐行 Read 6 个被修改文件 + 独立编译复验。只读，未改任何文件。

## 修复 1（Important：parser 内存风险）— ✅ 真实落地，无新问题

- **`ExcelParser.java:44-91`**：监听器内 `totalRows++` 计数，收集满 200 行后 `invoke` 直接 return 不再构建行 Map——内存上界恒定为 200 行，`totalRows` 仍为精确总数（继续扫完流，20MB 上限下 CPU 代价可接受，取舍合理）。`var listener` 捕获匿名类自身类型、读取其包级字段 `totalRows` 的写法是合法 Java 惯用法，编译验证通过。`CsvParser` 继承父类随之修复。
- **`MarkdownParser.java:17-21`**：`readNBytes(BYTE_LIMIT)`，`BYTE_LIMIT = 30000*4+1`。边界推演：UTF-8 单字符 ≤4 字节，读满上限必保证解码后 >30k 字符触发截断；恰好 30000 个 4 字节字符的文件读不满上限、判为全量（正确）；截断点落在多字节字符中间时 `new String(UTF_8)` 尾部产生 U+FFFD，但随后 `TextContent.of` 截取前 30000 字符将其丢弃，输出仍是完整合法字符。内存上界 ≈120KB。✅
- DocxParser/PdfParser 整文档入内存按计划不修（POI/PDFBox 无简单限量流式手段，20MB 白名单兜底），复审认可该取舍。

## 修复 2（Minor-1：异常逃逸）— ✅ 真实落地，无新问题

- **`FileReadTool.java:49-81`、`ImageReadTool.java:50-88`**：守卫校验（含 `getOwnedFile` 的 DB 访问）、开流、解析/OCR、JSON 序列化全链路包进单一 `catch (Exception)`，任何异常 log 后转 `{success:false, error}`；`guard.check` 内的 `BusinessException` 仍走 deny JSON 快路径，语义不变；try-with-resources 关闭 `openStream` 行为不变。初评 Minor-1 的缝隙已封死。

## 修复 3（Minor-5：Noop 语义）— ✅ 真实落地，无新问题

- **`OcrService.java:21-23`** 新增 `default boolean isEnabled() { return true; }`；**`NoopOcrService.java:19-22`** 覆写为 `false`；**`ImageReadTool.java:61-64`** 在打开 OSS 流之前短路，返回 `{success:false, error:"OCR 功能未启用（…）…"}`（提示文本取自 `recognize(new byte[0])`，对 Noop 与入参无关，行为正确）。不再以 success:true 返回占位提示，且省一次无用下载。短路发生在守卫与白名单校验之后，安全链路顺序正确。
- 风格备注（非问题）：用 `recognize(new byte[0])` 取提示文本略间接，独立 `disabledReason()` 方法更直白，但功能无缺陷，不要求再改。

## 阻断性发现

无。三项修复均真实落地，未引入新的 Critical/Important/Minor 问题。

## 编译独立复验（修复后）

`./mvnw clean compile -DskipTests -pl db-genius-agent -am`：common/model/service SUCCESS，agent FAILURE，错误仍恰好 5 个、全部位于 `DbWorkflowAgent.java` / `WorkflowHandler.java`（找不到 ExcelParseTool，Task 3 预期中间态），本轮修改的 6 个文件零错误。与实现者报告一致。

## 最终质量结论：**Approved**

初评的 1 个 Important 与 2 个被点名 Minor 已全部修复；遗留 Minor（PdfParser message 覆盖、10MB 读前预检、FileReadToolTest 单测等）均已记录且不阻断，按计划由 Task 3 / Phase 5 收尾。
