# Task 3 评审 — Phase 4：agent 接线与提示词改造

日期：2026-07-27
评审方式：只读静态评审（未改文件、无 git 写操作、未重跑编译——以静态抽查核实实现者报告的编译结论）。
评审输入：`docs/plan/oss-storage-and-file-tools-plan.md` §4 Phase 4 / §5、`.superpowers/sdd/task-3-report.md`、`.superpowers/sdd/task-3-diff.txt`、`.superpowers/sdd/task-2-report.md`，并对照全部改动后源码逐行核对（diff 与仓库当前文件一致）。

## 一、规格符合性（绑定约束逐条）

### 1. ToolCallAgent（`db-genius-agent/.../ToolCallAgent.java`）✅

- ✅ toolContext 经 `ToolCallingChatOptions.builder().toolContext(...)` 进入 chatOptions（`ToolCallAgent.java:50-57`），仅 `toolContext != null` 时追加。
- ✅ `internalToolExecutionEnabled(false)` 保持不变（`:51`）。
- ✅ 不传 toolContext 时行为与改造前一致：原 6 参构造函数保留，委托新重载并传 `null`（`:36-39`）；null 时跳过 `.toolContext(...)`，构建出的 options 与改造前等价。`DbSqlAgent` / `DbCompareAgent` 构造调用未动，编译兼容。
- ✅ `think()`（`:79`）与 `act()`（`:124`）均以该 `chatOptions` 构建 `Prompt`；`act()` 走 `toolCallingManager.executeToolCalls(prompt, response)`，Spring AI 从 prompt options 取 toolContext 注入声明了 `ToolContext` 参数的 `@Tool` 方法，与计划 §3.1 的落地方式一致。

### 2. DbWorkflowAgent（`db-genius-agent/.../DbWorkflowAgent.java`）✅

- ✅ `ExcelParseTool` 彻底移除：全仓库 `Grep "ExcelParseTool|getFilePath"` 仅剩 `ExcelParser.java:16` 一处注释提及（无代码引用）；import / 构造参数 / super 注册均已替换为 `FileReadTool` + `ImageReadTool`（`:20-36`）。
- ✅ toolContext 透传父类新重载（`:31`）。

### 3. 系统提示词 File Processing 段（`DbWorkflowAgent.java:71-82`）✅

- ✅ 适用格式与 @Tool 描述逐字对齐：readFile（xlsx/xls/csv/docx/pdf/md，`FileReadTool.java:40-41`）、readImage（png/jpg/jpeg/webp/bmp，`ImageReadTool.java:42-43`）；`file#N` → fileId 的用法与 `@ToolParam("The file ID, e.g. 12")` 一致。
- ✅ 含「You may ONLY use file#N numbers that actually appear in the conversation. Never guess or fabricate a file ID.」不得编造编号规则（`:80`），满足计划 Phase 4 第 4 条。
- ✅ 提示词全文（`buildSystemPrompt` + `buildNextStepPrompt` + WorkflowHandler 拼接的 enhancedMessage）无任何服务器路径、OSS key、userId。userId/allowedFileIds 只经 ToolContext 传递，满足 §5 安全清单相关三项。

### 4. WorkflowHandler（`db-genius-agent/.../intent/WorkflowHandler.java`）✅

- ✅ 入口属主校验：`handle()` 内对每个 fileId 调 `fileUploadService.getOwnedFile(fileId, userId)`（`:81-83`），`getOwnedFile` 实现（`FileUploadServiceImpl.java:68-77`）对不存在抛 404、非属主抛 403 `BusinessException`，整个请求拒绝。
- ✅ 提示词拼接为 `[file#N: originalName]` 逻辑引用（`:85-88`），无路径、无 OSS key。
- ✅ toolContext = `Map.of("userId"→Long userId, "allowedFileIds"→Set.copyOf(fileIds))`（`:90-92`），key 取自 `FileAccessGuard.CONTEXT_USER_ID/CONTEXT_ALLOWED_FILE_IDS` 常量，与 Task 2 契约一致；类型（Long / Set<Long>）与守卫读取逻辑（`FileAccessGuard.java:47-56` 的 Number / Collection 匹配）兼容。
- ✅ 无文件时 `toolContext = Map.of()`（非 null 空 Map，`:78`），readFile/readImage 守卫按"missing security context"拒绝——实现者报告已声明此为预期行为，合理（见 ⚠️-1）。
- ✅ 注入字段（`FileReadTool fileReadTool` + `ImageReadTool imageReadTool`，`:50-51`）与 `new DbWorkflowAgent(...)` 构造调用（`:103-105`）同步更新，import 增删正确。
- ✅ 无任何路径泄露：`getFilePath` 调用已删除。

### 5. 编译验证 ⚠️（采信实现者结论 + 静态抽查）

- 实现者报告全 reactor `./mvnw clean compile -DskipTests` BUILD SUCCESS（六模块）。本评审未重跑（评审约束为只读抽查），已静态核实关键调用点：新旧构造重载匹配、两个子类（DbSqlAgent/DbCompareAgent）不受影响、`ToolCallingChatOptions.Builder` 用法类型正确、PdfParser 的 `Loader.loadPDF(byte[])` 与 PDFBox 3.0.3（根 pom `pdfbox.version=3.0.3`）API 匹配。无矛盾点。

## 二、代码质量评审

### 已核实无问题项

- **不可变性 / 线程安全** ✅：`Map.of`（不可变）、`Set.copyOf`（不可变拷贝，防御请求 DTO 的 List 后续被修改）；`chatOptions` 为 agent 实例 `final` 字段，构造后不再变；agent 由 `WorkflowHandler.handle()` 每请求 `new`（`:103`），toolContext 每请求重建，无跨请求共享可变状态。
- **SSE 异步线程异常语义不劣化** ✅：`handle()` 在 IntentRouter 异步线程内执行（`IntentRouter.java:99-109`），`getOwnedFile` 抛出的 `BusinessException(403/404)` 与改造前已有的 `IllegalArgumentException`（如"工作流需要至少选择一个数据库配置"）走同一条路径：`dispatchToHandler` catch → `SseEvent.error` → `emitter.complete()`。且属主校验发生在 `getOrCreateConversation` **之前**（与改造前 filePaths 解析的位置相同），被拒绝的请求不会留下孤儿会话/消息记录。语义不劣化，实际是修复了原 IDOR 漏洞。
- **PdfParser 修复方式自洽** ✅：`Loader.loadPDF(in.readAllBytes())`（`PdfParser.java:23`）是对 PDFBox 3.0.3 无 `loadPDF(InputStream)` 重载的正确改法；与 Task 2 修复轮次 1 的内存防护结论自洽——该轮明确"POI/PDFBox 需整文档入内存，按计划不修，由上传 20MB 白名单兜底"，本次修复代码注释（`:21-22`）复述了同一取舍，未与既有决策冲突。
- **tool 异常不逃逸** ✅：FileReadTool/ImageReadTool 全链路 try-catch（Task 2 修复轮次 1 已做），toolContext 接线不会把异常引入 ReAct 循环。

### 发现（按严重度）

- Critical：无。
- Important：无。
- Minor-1：`WorkflowHandler.java:92` 的 `Set.copyOf(request.getFileIds())` 对含 null 元素的 List 会抛 NPE。实际不可达——上方 `:81-83` 的流先对每个 fileId 调 `getOwnedFile`，null 会先以 404 被拒；仅作防御性备注，不建议为此改代码。
- Minor-2：重复 fileId 会在提示词 `[Attached files: ...]` 中重复列出（`Set.copyOf` 会去重白名单但 files 列表不会）。行为无害（白名单仍正确、属主校验逐次通过），仅提示词冗余。

## 三、⚠️ 待确认项

1. **无文件会话传空 Map 而非 null**：`Map.of()` 使 chatOptions 携带空 toolContext，模型若幻觉调用 readFile/readImage 会被守卫以 "missing security context" 拒绝——实现者声明为预期行为，评审认同（拒绝文本对模型可理解、不打断循环），但与"不传 toolContext 时行为与改造前一致"的 null 路径存在细微差别（空 Map vs 不设 toolContext）。已在源码静态核实 Spring AI 对空 toolContext 无副作用；如有疑虑可在真实冒烟中观察一次。
2. **编译结论未独立复跑**：评审按要求以静态抽查代替，若 Phase 5 前要发布，建议以最终代码再跑一次全量 `./mvnw clean compile -DskipTests`。
3. **越权请求的 SSE 报错文案为英文**（"File not found" / "No permission to access this file"，来自 `FileUploadServiceImpl`），与项目其他中文报错风格不一致——属 Task 1 产物，非本任务范围，列出供 Phase 5 收尾参考。

## 四、结论

- **规格结论**：✅ 全部绑定约束满足，无不符项（编译项为 ⚠️ 采信实现者报告 + 静态抽查一致）。
- **质量结论**：**Approved**。无 Critical/Important 发现；2 条 Minor 均不建议修改。
- Task 2 遗留编译错误（PdfParser）的顺手修复正确且必要，未越出本任务合理范围。
