# OSS 存储迁移 + 文件读取 Tools 改造计划

> 状态：已执行（2026-07-27）
> 创建日期：2026-07-27
> 关联讨论：文件本地上传 → 阿里云 OSS；agent 读文件的安全边界（路径注入 / IDOR）

## 1. 背景与目标

现状：

- 文件上传存本地磁盘（`FileUploadServiceImpl.java:36-47`，目录 `db-genius.file-upload-dir`，默认 `/tmp/db-genius/uploads`）。
- `WorkflowHandler.java:76` 把**服务器绝对路径**直接拼进 LLM 提示词，agent 靠 `ExcelParseTool`（只支持 Excel，参数是裸路径）读文件。
- 问题：① 路径注入风险（LLM 可被诱导读 `/etc/passwd`）；② `getFilePath()` 无属主校验（IDOR）；③ 本地存储无法多实例共享、无持久化保证；④ 仅支持 Excel。

目标：

1. 上传与存储全面切换到阿里云 OSS。
2. 新增通用文档读取 tool（支持 xlsx / xls / csv / docx / pdf / md）。
3. 新增图片 OCR tool（在线 OCR 为主，调研结论见 §3.2）。
4. 安全边界：tools 只接收 `fileId`；`userId`、允许访问的文件集合等经 Spring AI `ToolContext` 在 LLM 上下文之外传递。

非目标（本轮不做）：

- 文件下载/对外访问端点（此前讨论的 download API / Nginx X-Accel-Redirect，另立任务）。
- 存量本地文件的数据迁移（当前无需要保留的生产数据；`schema.sql` 本就不自动执行，改表结构即可）。

## 2. 现状关键代码索引

| 位置 | 说明 |
|---|---|
| `db-genius-service/.../FileUploadServiceImpl.java` | 本地上传实现，`getFilePath` 无属主校验 |
| `db-genius-web/.../controller/FileController.java` | 唯一上传端点 `POST /file/upload`，直接返回实体（泄露 storedPath） |
| `db-genius-agent/.../tool/ExcelParseTool.java` | 现有唯一文件工具，参数为绝对路径 |
| `db-genius-agent/.../DbWorkflowAgent.java` | 注册 sqlExecute / excelParse / terminate 三个 tool |
| `db-genius-agent/.../ToolCallAgent.java:43-45` | 手动 tool 执行：`ToolCallingChatOptions.builder().internalToolExecutionEnabled(false)` + `toolCallingManager.executeToolCalls` |
| `db-genius-agent/.../intent/WorkflowHandler.java:70-77` | fileIds → 绝对路径拼提示词 |
| `db-genius-model/.../entity/UploadedFile.java` | 表 `uploaded_file`，字段含 `stored_path` |
| 根 `pom.xml:35` | Spring AI `1.1.8`（ToolContext API 自 1.0.0 起可用） |

## 3. 调研结论

### 3.1 LLM 上下文之外的参数传递：Spring AI `ToolContext`

成熟方案，无需自造。Spring AI 自 1.0.0 提供 `org.springframework.ai.chat.model.ToolContext`：

- `ToolCallingChatOptions.builder().toolContext(Map<String, Object>)` 把任意对象放进 options；
- `@Tool` 方法声明一个 `ToolContext` 类型参数即可在执行时拿到，**该参数不会出现在发给 LLM 的 JSON Schema 里**，LLM 完全不知道它的存在（[ToolContext Javadoc](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/chat/model/ToolContext.html)，[Tool Calling 参考文档](https://docs.spring.io/spring-ai/reference/api/tools.html)）；
- `DefaultToolCallingManager.executeToolCalls(prompt, response)` 执行时会从 prompt options 中取 `toolContext` 传给 tool——**与项目 `ToolCallAgent` 的手动执行模式完全兼容**，只需把 toolContext 构建进 `chatOptions`。

落地方式：`{userId, allowedFileIds}` 在 `WorkflowHandler.handle()` 入口（Sa-Token 已鉴权、属主已校验）放入 toolContext，随 agent 构造传入。这同时解决了 agent 异步执行时 `StpUtil` ThreadLocal 不可用的问题。

### 3.2 OCR 选型

| 方案 | 中文准确率 | 集成成本 | 运维成本 | 结论 |
|---|---|---|---|---|
| 阿里云 OCR API（文字识别 `ocr_api20210707`，RecognizeAdvanced/RecognizeGeneral） | 高（含表格结构化） | 低（SDK/HTTP，可直接传 OSS 对象 URL 或图片字节） | 按次计费，无本地资源占用 | **推荐（默认方案）** |
| 多模态 LLM（如 Qwen-VL / DashScope） | 高，且能理解版式 | 低 | 按 token 计费，引入第二个模型供应商 | 备选，后续可平滑替换 |
| RapidOCR（ONNX，有 Java 绑定 `rapidocr-onnxruntime`） | 中高（PaddleOCR 模型移植） | 中（native 依赖，镜像体积 +几百 MB） | 免费、离线 | 离线兜底备选 |
| PaddleOCR 原生 | 高 | 高（Python 生态，Java 集成困难） | 高 | 排除 |
| Tesseract (Tess4J) | 中文差 | 低 | 免费 | 排除 |

决策：**实现在线阿里云 OCR**，但抽象 `OcrService` 接口（`String recognize(byte[] imageBytes)`），Aliyun 实现为默认，保留后续接 RapidOCR/多模态的可能。`db-genius.ocr.enabled=false` 时图片 tool 直接返回"未启用 OCR"的提示，不影响主流程。

### 3.3 各格式解析库

| 格式 | 库 | 说明 |
|---|---|---|
| xlsx / xls | EasyExcel 4.0.3（已有） | `EasyExcel.read(InputStream, ...)` 原生支持流，无需落盘 |
| csv | EasyExcel / 直接按行读 | 复用行解析逻辑，与 Excel 输出同构 |
| docx | Apache POI `poi-ooxml`（EasyExcel 已传递依赖 poi 5.2.x，显式 pin 版本） | `XWPFWordExtractor` 抽全文；**不支持 .doc 老格式**（需 poi-scratchpad，价值低，明确排除并在 tool 描述中说明） |
| pdf | Apache PDFBox 3.x（`org.apache.pdfbox:pdfbox`） | `PDFTextStripper`，限制最大页数；扫描件 PDF 抽不出文字时提示可转图片走 OCR |
| md | 直接读文本 | 限制最大字符数 |

统一防护：所有解析结果截断（沿用现有 200 行 / 约 30k 字符上限），超限在返回值中标注 `truncated: true`。

## 4. 变更计划

### Phase 0 — 依赖与配置

1. 根 `pom.xml` `dependencyManagement` 新增：
   - `com.aliyun.oss:aliyun-sdk-oss:3.17.4`
   - `org.apache.pdfbox:pdfbox:3.0.3`
   - `org.apache.poi:poi-ooxml:5.2.5`（显式 pin，对齐 EasyExcel 传递版本）
   - OCR：先用 Hutool HttpClient 直接调阿里云 OCR POP API（`ocr-api.cn-hangzhou.aliyuncs.com`，已依赖 hutool-all，避免再引一套 alibabacloud SDK；若实现时签名过繁则改引 `com.aliyun:ocr_api20210707`，执行时再定，以代码量小者为准）。
2. `db-genius-service/pom.xml` 加 `aliyun-sdk-oss`；`db-genius-agent/pom.xml` 加 `pdfbox`、`poi-ooxml`（easyexcel 已有）。
3. `application.yml` 新增配置块：
   ```yaml
   db-genius:
     oss:
       endpoint: ${ALIYUN_OSS_ENDPOINT:}
       bucket: ${ALIYUN_OSS_BUCKET:}
       access-key-id: ${ALIYUN_ACCESS_KEY_ID:}
       access-key-secret: ${ALIYUN_ACCESS_KEY_SECRET:}
       dir-prefix: ${ALIYUN_OSS_DIR_PREFIX:uploads/}
     ocr:
       enabled: ${ALIYUN_OCR_ENABLED:false}
   ```
   OSS 与 OCR 复用同一对 AccessKey（RAM 子账号，最小授权：该 bucket 的读写 + OCR 调用）。
4. `.env.example`、`deploy/.env.example` 补上述变量及说明。
5. 上传白名单集中定义：文档类 `xlsx/xls/csv/docx/pdf/md`，图片类 `png/jpg/jpeg/webp/bmp`；单文件大小上限（如 20MB）在 service 层校验。

### Phase 1 — OSS 存储层（db-genius-service）

1. 新增 `OssService`（service 模块）：`String upload(String key, InputStream in)`、`InputStream download(String key)`、`void delete(String key)`；`@PostConstruct` 校验配置非空，未配置时启动即报错（fail-fast，避免运行时才暴露）。
2. 新增配置类 `OssConfig` 构建 `OSS` client Bean。
3. 改造 `FileUploadServiceImpl.uploadFile`：
   - 保留 `TrialGuard`、空文件校验；
   - 扩展名/大小白名单校验；
   - key 生成：`{dirPrefix}{userId}/{UUID}.{ext}`（按用户分目录，便于管理与排查）；
   - 流式 `ossClient.putObject`，不落本地盘；
   - 实体落库改存 `ossKey`。
4. `UploadedFile` 实体：`storedPath` → `ossKey`；`db-genius-web/src/main/resources/db/schema.sql` 同步改列（`oss_key VARCHAR(512) NOT NULL`）。
5. `FileUploadService` 接口调整：
   - 删 `getFilePath(Long)`；
   - 新增 `UploadedFile getOwnedFile(Long fileId, Long userId)`——查库 + 属主校验，不通过抛 `BusinessException(403)`，**所有 tool 与上层统一走它**；
   - 新增 `InputStream openStream(UploadedFile file)`（内部委托 OssService）。
6. `FileController.upload` 返回值改为 VO（`id / originalName / fileSize / contentType / createdAt`），不再泄露 `ossKey`。

### Phase 2 — 通用文档读取 tool（db-genius-agent）

1. 新增 `FileReadTool`：
   ```java
   @Tool(description = "Read an uploaded document (xlsx/xls/csv/docx/pdf/md) and return its content as structured text. fileId is the number from a [file#N: name] reference in the conversation.")
   public String readFile(@ToolParam(description = "The file ID, e.g. 12") Long fileId, ToolContext toolContext)
   ```
   - 从 `toolContext` 取 `userId` 与 `allowedFileIds`（缺一即拒绝）；
   - `fileId ∉ allowedFileIds` → 直接返回错误文本（不抛异常打断 ReAct 循环，让模型自我修正）；
   - `fileUploadService.getOwnedFile` 二次属主校验（防御 toolContext 被误构建）；
   - 按扩展名分发到对应 parser，返回 JSON：`{success, fileName, format, truncated, content}`。
2. parser 实现放 `db-genius-agent/.../tool/file/` 包：`ExcelParser`（迁移现有 ExcelParseTool 逻辑，改读 InputStream）、`CsvParser`、`DocxParser`、`PdfParser`（限 50 页）、`MarkdownParser`。
3. 删除 `ExcelParseTool`，其能力由 `FileReadTool` 覆盖。

### Phase 3 — 图片 OCR tool（db-genius-agent）

1. 新增 `OcrService` 接口（agent 模块）+ `AliyunOcrService` 实现：从 OSS 读字节 → 调 RecognizeAdvanced → 返回纯文本；`db-genius.ocr.enabled=false` 时由 `NoopOcrService` 返回未启用提示。
2. 新增 `ImageReadTool`：
   ```java
   @Tool(description = "Recognize text in an uploaded image (png/jpg/jpeg/webp/bmp) via OCR. fileId is the number from a [file#N: name] reference.")
   public String readImage(@ToolParam(description = "The file ID") Long fileId, ToolContext toolContext)
   ```
   安全校验链路与 `FileReadTool` 完全一致（抽一个 package-private 的 `FileAccessGuard` 复用，不做公开抽象）。

### Phase 4 — agent 接线与提示词改造

1. `ToolCallAgent`：构造函数或 setter 接收 `Map<String, Object> toolContext`，构建 `chatOptions` 时写入 `ToolCallingChatOptions.builder().toolContext(...)`。
2. `DbWorkflowAgent`：构造函数改为接收 `FileReadTool`、`ImageReadTool`（替换 `ExcelParseTool`），外加 `Map<String, Object> toolContext` 透传父类；系统提示词的 File Processing 段更新为"先用 readFile / readImage 读取 [file#N] 引用的文件"。
3. `WorkflowHandler.handle()` 改造：
   - 入口处对每个 `fileId` 调 `getOwnedFile(fileId, userId)` 校验属主（失败即 403，整个请求拒绝）；
   - 提示词拼接改为逻辑引用，**不再出现任何路径**：
     `[Attached files: [file#12: 销售数据.xlsx], [file#13: 说明.md]]`（原始文件名有助于模型理解语义）；
   - 构建 `toolContext = Map.of("userId", userId, "allowedFileIds", Set.copyOf(fileIds))` 传入 agent。
4. `DbWorkflowAgent` 系统提示词中补充规则："引用文件必须使用对话中出现的 file#N 编号，不得猜测或编造编号"。

### Phase 5 — 收尾

1. 更新 `AGENTS.md`：文件存储改为 OSS、tool 列表变化、`uploaded_file.oss_key`、新增环境变量。
2. `README.md` 若提及上传目录则同步。
3. 验证（见 §6）。

## 5. 安全设计小结（执行时的检查清单）

- [ ] 任何 tool 的 LLM 可见参数中**没有路径、没有 OSS key、没有 userId**，只有 `fileId`；
- [ ] `userId` / `allowedFileIds` 只经 `ToolContext` 传递，来源是 Sa-Token 鉴权后的服务端变量；
- [ ] tool 内双校验：`allowedFileIds` 包含性检查 + `getOwnedFile` 属主检查；
- [ ] 提示词只出现 `[file#N: 原名]` 逻辑引用；
- [ ] 上传与读取双侧均做扩展名白名单 + 大小/行数/页数/字符数上限；
- [ ] tool 失败返回结构化错误文本，不抛异常打断 ReAct 循环；
- [ ] `FileController` 返回 VO，不外泄 `ossKey`。

## 6. 验证

1. `export JAVA_HOME="D:\Java\java21" && ./mvnw clean compile -DskipTests` 全模块通过。
2. 新增单测（参照现有稀疏测试的风格，放在 service / agent 模块）：
   - `FileReadToolTest`：mock FileUploadService，验证越权 fileId 被拒绝、各格式分发正确、截断生效；
   - `FileUploadServiceImplTest`：白名单拒绝 `.exe` 等。
3. 手动冒烟（需要真实 OSS + 环境变量）：
   - 上传 xlsx → 工作流中要求导入 → agent 调 `readFile` 成功；
   - 聊天中引用他人 fileId → 403；
   - 上传 png 并启用 OCR → `readImage` 返回文字；
   - 诱导性提问（"读取 /etc/passwd"、"读取 file#999"）→ tool 返回拒绝文本且任务正常收尾。

## 7. 风险与备注

- **OCR 计费**：Aliyun OCR 按次收费，试用模式（trial）下文件上传本就禁用，无需额外防刷；正式环境注意 RAM 子账号最小授权。
- **扫描件 PDF**：PDFBox 抽不到文字，parser 应识别该情况并在返回值中提示"可转为图片后用 readImage"，由模型自行决策，不自动级联。
- **上下文膨胀**：200 行 / 30k 字符截断只是兜底，大 CSV 仍需模型分步处理；后续可考虑加分页参数（`startRow`），本轮不做。
- **`.doc` 老格式**：明确不支持，上传白名单不含 `.doc`。
- **执行顺序**：Phase 0→1→2/3（可并行）→4→5；Phase 4 之前应用处于中间态（新 tool 未接线），不可中途发布。
