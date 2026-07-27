# Task 3 报告 — Phase 4：agent 接线与提示词改造

日期：2026-07-27
计划来源：`docs/plan/oss-storage-and-file-tools-plan.md` §4 Phase 4 / §5
约束遵守：全程无任何 git 操作；改动文件仅限本任务范围 + 一处 Task 2 潜伏编译错误的必要修复（见下）。

## 一、三个文件的改动要点

### 1. `db-genius-agent/.../ToolCallAgent.java`

- 原构造函数（`DbSqlAgent`/`DbCompareAgent` 在用）签名**保持不变**，改为委托给新增的重载构造函数，传 `toolContext = null`——不传时行为与改造前完全一致（null 安全）。
- 新增重载构造函数：`ToolCallAgent(String name, String systemPrompt, String nextStepPrompt, int maxSteps, ChatClient chatClient, Map<String, Object> toolContext, Object... toolObjects)`。
- `chatOptions` 构建改为：`ToolCallingChatOptions.builder().internalToolExecutionEnabled(false)`，当 `toolContext != null` 时追加 `.toolContext(toolContext)`。
- `think()`（`ToolCallAgent.java:67`）与 `act()`（`ToolCallAgent.java:112`）均用这个 `chatOptions` 构建 `Prompt`；`DefaultToolCallingManager.executeToolCalls` 执行时自动从 options 取 toolContext 传给声明了 `ToolContext` 参数的 `@Tool` 方法——**无需额外接线**。

### 2. `db-genius-agent/.../DbWorkflowAgent.java`

- 构造函数签名改为：`DbWorkflowAgent(ChatClient, SqlExecuteTool, FileReadTool, ImageReadTool, TerminateTool, String dbDocContext, boolean hasFiles, Map<String, Object> toolContext)`；`toolContext` 透传父类新重载。
- 注册到 super 的 tool 列表：`sqlExecuteTool, fileReadTool, imageReadTool, terminateTool`（`ExcelParseTool` 已移除，import 同步清理）。
- 系统提示词 File Processing 段更新（最终文本见下节）；`buildNextStepPrompt` 文案原本就是通用表述（"If the file has been parsed..."），未动。

### 3. `db-genius-agent/.../intent/WorkflowHandler.java`

- 注入字段 `ExcelParseTool excelParseTool` → `FileReadTool fileReadTool` + `ImageReadTool imageReadTool`（均为 `@Component`，Spring 构造注入经 `@RequiredArgsConstructor`）。
- 删除 `fileUploadService::getFilePath` 调用与绝对路径拼接逻辑。
- 入口属主校验：对每个 fileId 调 `fileUploadService.getOwnedFile(fileId, userId)`，失败抛 403/404 整个请求拒绝；收集 `UploadedFile` 列表。
- 提示词拼接改为逻辑引用：`[Attached files: [file#12: 销售数据.xlsx], [file#13: 说明.md]]`（id + originalName，无路径、无 OSS key）。
- 构建 `toolContext = Map.of(FileAccessGuard.CONTEXT_USER_ID, userId, FileAccessGuard.CONTEXT_ALLOWED_FILE_IDS, Set.copyOf(request.getFileIds()))`（常量值即 `"userId"` / `"allowedFileIds"`，与 Task 2 契约一致）；无文件时 `toolContext = Map.of()`——非 null 空 Map，此时 readFile/readImage 的守卫会因缺 userId/allowedFileIds 拒绝访问，为预期行为。
- 构造 `DbWorkflowAgent` 时传入新 tool 与 toolContext。
- 新增 import：`FileReadTool`、`ImageReadTool`、`FileAccessGuard`、`UploadedFile`、`Map`、`Set`；删除 `ExcelParseTool` import。

## 二、系统提示词 File Processing 段最终文本

仅 `hasFiles == true` 时拼接（`DbWorkflowAgent.buildSystemPrompt`）：

```
## File Processing
- Attached files are given in the conversation as [file#N: fileName] references.
- Use the readFile tool to read documents (xlsx/xls/csv/docx/pdf/md); use the readImage tool to recognize text in images (png/jpg/jpeg/webp/bmp). Pass the number N as the fileId argument.
- First read the attached file(s), then analyze the data structure (columns, types, sample data).
- Plan the SQL operations based on the data.
- Execute the operations and verify results.
- You may ONLY use file#N numbers that actually appear in the conversation. Never guess or fabricate a file ID.
```

## 三、toolContext 构建与传递链路

```
WorkflowHandler.handle()
  │  Sa-Token 已鉴权得到 userId；getOwnedFile(fileId, userId) 逐文件属主校验（403/404 即拒）
  │  toolContext = Map.of("userId"→Long userId, "allowedFileIds"→Set<Long>)
  ▼
new DbWorkflowAgent(..., toolContext)
  │  透传 super(name, ..., chatClient, toolContext, tools...)
  ▼
ToolCallAgent：toolContext != null 时写入 ToolCallingChatOptions.builder().toolContext(...)
  │  chatOptions 被 think() / act() 用于构建 Prompt
  ▼
act()：toolCallingManager.executeToolCalls(prompt, response)
  │  Spring AI 从 prompt options 取 toolContext，注入声明了 ToolContext 参数的方法
  ▼
FileReadTool.readFile(Long fileId, ToolContext) / ImageReadTool.readImage(Long fileId, ToolContext)
  │  FileAccessGuard.check：context 身份/白名单 → fileId 包含性 → getOwnedFile 二次属主校验
```

LLM 可见参数只有 `fileId`；`ToolContext` 参数不出现在发给模型的 JSON Schema 中。无文件会话传 `Map.of()`，tool 调用会被守卫拒绝（正确行为）。

## 四、编译验证

环境：`export JAVA_HOME="D:\Java\java21"`（git-bash，Windows）。

命令：`./mvnw clean compile -DskipTests`（全 reactor，非 `-pl` 子集）

结果：**BUILD SUCCESS**，六个模块（root / common / model / service / agent / web）全部 SUCCESS，耗时约 48s。中间态消除，编译恢复绿色。

### 过程中发现并修复的一处 Task 2 潜伏错误

首轮全量编译在 agent 模块报 1 个错误：`PdfParser.java:[21,42]` —— `Loader.loadPDF(InputStream)` 不存在该重载（PDFBox 3.0.3 仅提供 `byte[]` / `File` / `RandomAccessRead` 版本）。Task 2 当时只编到 ExcelParseTool 符号缺失即中止（javac 报错不完整，task-2-report 的"坑"一节已预警），故未暴露。

修复（`tool/file/PdfParser.java:21`）：改为 `Loader.loadPDF(in.readAllBytes())`，并加注释说明（上传白名单 20MB 上限兜底内存；POI/PDFBox 本就需整文档入内存，与 Task 2 修复轮次 1 的取舍一致）。这是让全项目编译恢复绿色的必要改动，属本任务验收点的直接要求。

修复后重跑同一命令：BUILD SUCCESS。

## 五、残留检查

- `Grep "ExcelParseTool|getFilePath"`（全仓库 *.java）：仅剩 `ExcelParser.java:16` 一处注释提及"迁移自已删除的 ExcelParseTool"，无代码引用。
- 计划 §5 安全清单 Phase 4 相关项：✅ 提示词只出现 `[file#N: 原名]`；✅ userId/allowedFileIds 只经 ToolContext 传递；✅ tool 内双校验（Task 2 已实现并经本轮链路接通）。

## 遗留问题（交后续任务）

- 计划 §6 的单测（`FileReadToolTest` 等）仍未写，属 Phase 5/后续验收。
- Phase 5 收尾项未动：`AGENTS.md`（OSS 存储、tool 列表变化、`oss_key`、新环境变量）、`README.md` / `api-docs.yaml` 旧字段、`docker-compose.yml` 的 `DB_GENIUS_FILE_DIR`、`db-genius.ocr.endpoint` 配置补注。
- 手动冒烟（真实 OSS + OCR）未做，需环境变量齐备后在 Phase 5 验证。
