# 最终整体评审：OSS 存储迁移 + 文件读取 Tools

> 评审日期：2026-07-27
> 范围：计划 `docs/plan/oss-storage-and-file-tools-plan.md` 全量实现（Task 1–4），跨任务终审。
> 评审方式：只读；全量 diff + 全部新增文件逐一 Read + 官方文档核实 + 全量 `./mvnw test` 复跑。

## 总体结论

**需修复后合并（仅 1 个 blocker）**：OCR endpoint 默认值错误（见 C），修复面为 5 处一行式默认值改动，修完即可合并。其余计划项、安全清单、跨任务一致性全部通过，无其他阻断问题。

---

## A. 计划 §5 安全清单逐条终审（7/7 通过）

| # | 检查项 | 结论 | 证据 |
|---|---|---|---|
| 1 | tool 的 LLM 可见参数无路径/OSS key/userId | ✅ | `FileReadTool.readFile(Long fileId, ToolContext)`、`ImageReadTool.readImage(Long fileId, ToolContext)`；ToolContext 参数不进 JSON Schema（Spring AI 设计保证） |
| 2 | userId/allowedFileIds 只经 ToolContext 传递，来源为 Sa-Token 鉴权变量 | ✅ | 链路闭合：`ChatController.java:33 StpUtil.getLoginIdAsLong()` → `IntentRouter.route(request, userId)` → `WorkflowHandler.handle(..., userId)` → `toolContext = Map.of(CONTEXT_USER_ID, userId, ...)` → `DbWorkflowAgent` → `ToolCallAgent.chatOptions`（`ToolCallingChatOptions.builder().toolContext(...)`）→ `act()` 中 `new Prompt(messageList, chatOptions)` → `toolCallingManager.executeToolCalls(prompt, ...)`。异步执行不依赖 StpUtil ThreadLocal |
| 3 | tool 内双校验 | ✅ | `FileAccessGuard.check()`：allowedFileIds 包含性检查（`:53-56`）+ `fileUploadService.getOwnedFile` 属主二次校验（`:64`），缺一即拒绝 |
| 4 | 提示词只出现逻辑引用 | ✅ | `WorkflowHandler.java:85-89` 拼 `[file#N: 原名]`；全仓 grep 无任何路径/OSS key 入提示词 |
| 5 | 双侧白名单 + 上限 | ✅ | 上传侧：`FileUploadServiceImpl` 扩展名白名单 + 20MB；读取侧：扩展名分发白名单、图片 `isImage` 检查、Excel 200 行/文本 30k 字符/PDF 50 页/图片 10MB。两侧同一来源 `FileTypes` |
| 6 | tool 失败返回结构化错误文本，不抛异常 | ✅ | 两个 tool 均为全链路 try-catch(Exception) → errorJson；`FileAccessGuard` 不抛异常；`AliyunOcrService` 抛出的 IllegalStateException 在 tool 层被兜住 |
| 7 | Controller 返回 VO | ✅ | `FileController.upload` 返回 `UploadedFileVO`（id/originalName/fileSize/contentType/createdAt），不含 ossKey、userId；`api-docs.yaml` 已同步 |

## B. 跨任务一致性

- **FileTypes 白名单单一来源**：上传侧（`FileUploadServiceImpl`）与读取侧（`FileReadTool` 分发、`ImageReadTool.isImage`）同用 `com.dbgenius.model.constant.FileTypes`，✅。
- **ToolContext key 常量**：`WorkflowHandler` 写入侧与 `FileAccessGuard` 读取侧、单测均引用同一对常量 `CONTEXT_USER_ID`/`CONTEXT_ALLOWED_FILE_IDS`，无字符串散落，✅。
- **schema.sql ↔ 实体 ↔ mapper**：`uploaded_file.oss_key VARCHAR(512) NOT NULL` ↔ `UploadedFile.ossKey`（map-underscore-to-camel-case 自动映射）↔ `UploadedFileMapper` 仅继承 `BaseMapper` 无自定义 SQL，✅。
- **配置 key 三处一致**：`db-genius.oss.*`（5 项）与 `db-genius.ocr.enabled/endpoint` 在 `application.yml`、`OssProperties`/`OcrConfig @Value`、`.env.example`/`deploy/.env.example`/`docker-compose.yml` 之间一一对应，✅（默认值本身的对错见 C）。
- **依赖版本统一管理**：aliyun-sdk-oss 3.17.4、ocr_api20210707 3.1.3、pdfbox 3.0.3、poi-ooxml 5.2.5 均在根 pom `dependencyManagement`，✅。OCR 选型走了计划允许的备选路径（直接引 SDK 而非 Hutool 手写签名），属计划明示的"执行时再定"，✅。
- **旧资产清除**：`ExcelParseTool`/`getFilePath`/`storedPath`/`DB_GENIUS_FILE_DIR`/`file-upload-dir` 在活跃代码与配置中零残留（仅历史文档命中，不属本次范围）；`Dockerfile`/`.dockerignore`/deploy 脚本无 uploads 残留，✅。

## C. 疑点终审：OCR endpoint 默认值 —— 确认是真实 bug（Blocker）

**代码事实**：`AliyunOcrService.java:27` `config.endpoint = endpoint` 原样生效；默认值链为 `OcrConfig.java:21` `@Value("${db-genius.ocr.endpoint:ocr.cn-hangzhou.aliyuncs.com}")` ← `application.yml` `${ALIYUN_OCR_ENDPOINT:ocr.cn-hangzhou.aliyuncs.com}` ← env 示例/compose/README 同值。

**官方文档核实**：
- ocr-api（API 版本 2021-07-07，即 `ocr_api20210707` SDK、RecognizeAdvanced 所在产品）的官方服务接入点是 `ocr-api.cn-hangzhou.aliyuncs.com`（VPC 为 `ocr-api-vpc.cn-hangzhou.aliyuncs.com`）——[服务接入点官方文档](https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-endpoint)、[OpenAPI 门户 SDK 示例](https://next.api.aliyun.com/api-tools/sdk/ocr-api?version=2021-07-07)、[阿里云开发者社区问答](https://developer.aliyun.com/ask/658908) 三处一致，且社区明确"没有别的接入点"。
- `ocr.cn-hangzhou.aliyuncs.com` 属于旧版 OCR 产品（API 2019-12-30，SDK `ocr20191230`）。

**失败机理**：Tea SDK 把 `Action=RecognizeAdvanced&Version=2021-07-07` 发往配置的 host；旧产品网关不承载该版本/Action，返回 InvalidAction/InvalidVersion 类错误 → `AliyunOcrService` 抛 IllegalStateException → `readImage` 每次返回 "OCR 识别失败" 错误 JSON。**即：用户按默认配置开启 `ALIYUN_OCR_ENABLED=true` 后，OCR 功能必然不可用**。

**加重因素**：计划 Phase 0 第 1 条原文写的就是 `ocr-api.cn-hangzhou.aliyuncs.com`；Task 4 执行时发现了代码与文档不一致，但按"以代码为准对齐"把错误默认值扩散到了 yml/env/compose/README 五处。

**结论**：真实 bug，**合并前必须修**。修复面小且机械：5 处默认值 `ocr.cn-hangzhou...` → `ocr-api.cn-hangzhou...`（`OcrConfig.java:21`、`application.yml`、`.env.example`、`deploy/.env.example`、`docker-compose.yml`、README 说明行）。缓解因素：OCR 默认关闭，endpoint 可用 env 显式覆盖——但默认值错误的新功能不应带病合入。

## D. 遗留 Minor 逐条裁决

| # | 事项 | 裁决 | 理由 |
|---|---|---|---|
| 1 | `uploadFile` catch(IOException) 捕获不到 OSSException/ClientException | **后续迭代** | 功能正确性不受影响（异常仍会抛出并由全局异常处理返回错误），只是错误语义从"上传失败"退化为 SDK 原始 500；建议把 `ossService.upload` 调用包进 try-catch(RuntimeException) 统一转 BusinessException |
| 2 | OSS 上传成功但 DB save() 失败无补偿删除（孤儿对象） | **后续迭代** | 触发窗口窄（仅 DB 写失败瞬间），代价为 OSS 残留对象占存储；建议 save 失败时补偿 `ossService.delete(ossKey)`，或 bucket 侧配生命周期规则兜底。不阻断合并 |
| 3 | PdfParser 两个 message 互相覆盖、扫描件判断只看前 50 页 | **可接受**（后续顺手修） | 覆盖发生在"超 50 页且恰好前 50 页无文字"的边角场景，仅提示信息丢失一个，success/truncated 均正确；前 50 页是合理启发式。修法：两个提示分开字段或合并拼接 |
| 4 | 10MB 图片上限未用 DB fileSize 读前预检 | **可接受** | `readNBytes(MAX+1)` 限量读取 + try-with-resources 关流，行为正确，只是超限图片多走 ≤10MB 下载带宽；预检是优化不是正确性问题 |
| 5 | 两个 tool 成功 JSON 结构略不齐 | **可接受** | 沿用旧 ExcelParseTool 输出风格（task-2 评审已记录为合理偏差），LLM 可按 format/字段分支理解 |
| 6 | FileAccessGuard 为 public | **可接受** | 计划原意 package-private，但 `tool` 包与 `tool.file` 包分离导致跨包注入必须 public，属有记录的必要偏差 |
| 7 | 越权错误文案为英文 | **可接受** | 抽查证伪了"项目其余文案中文居多"的前提：`UserServiceImpl`、`DbConfigServiceImpl`、`MongoDbAdapter`、`ConversationServiceImpl` 等 BusinessException 文案绝大多数为英文；且 tool 返回文本的读者是 LLM，英文更合适 |
| 8 | WorkflowHandler 重复 fileId 提示词重复列出 | **可接受** | 无害：toolContext 用 `Set.copyOf` 已去重，提示词重复仅略冗长 |

## E. 整体缺陷扫描（任务级视角之外的新发现）

1. **【Blocker，同 C】** OCR endpoint 默认值错误，开启即失败。见 C。
2. **【Minor/后续迭代】接线无测试保护**：Sa-Token userId → WorkflowHandler toolContext 构建 → DbWorkflowAgent/ToolCallAgent 透传这条最关键的链路没有任何单测；`FileReadToolTest` 只测了 tool 内部（手动构造 ToolContext）。若未来回归删掉了 toolContext 透传，单测全绿而功能静默失效（所有文件访问被拒），只能靠手动冒烟发现。建议后续补一个 `WorkflowHandler` 单测（mock FileUploadService/ConversationService，捕获构造的 DbWorkflowAgent 或断言 toolContext 内容）。
3. **【已核实非问题】** `Set.copyOf(request.getFileIds())` 的 null 元素 NPE：不可达——同方法内 files 流先对每个 fileId 调 `getOwnedFile`，null 会先触发 404。与 task-3 评审结论一致，确认不修。
4. **【验证缺口】** 计划 §6.3 手动冒烟（真实 OSS 上传/越权诱导/OCR 识别）未执行，依赖真实阿里云资源。**C 修复后必须在真实环境补冒烟**，尤其 OCR 路径（这是唯一能端到端证实 endpoint 修复的手段）。
5. **并发/资源/异常路径复扫**（无新缺陷）：两个 tool 均为无状态单例、parser 每次调用新建；OSS client 线程安全单 Bean（`destroyMethod="shutdown"`）；所有 OSS 流均 try-with-resources 关闭；ExcelParser 有界收集（200 行）+ MarkdownParser `readNBytes` 限量读，内存上界受 20MB 上传上限约束；静态 ObjectMapper 仅做序列化，线程安全。
6. **测试断言质量**：两个新测试类断言聚焦行为（拒绝路径 verify 不触达 OSS/DB、成功路径校验 JSON 关键字段），无"只跑不断言"的空洞；风格与项目既有稀疏测试一致。

## 验证记录

- `./mvnw test`（JAVA_HOME=D:\Java\java21，git-bash，`set -o pipefail`）全量复跑：**BUILD SUCCESS**，common 8/8、model 5/5、service 49/49（含新增 `FileUploadServiceImplTest` 3/3）、agent 14/14（含新增 `FileReadToolTest` 5/5），零失败零跳过。日志中 `SqlExecuteToolTest` 的 ERROR 输出是该测试故意触发失败路径的预期日志，与改造前一致。
- 注：首次后台复跑命令误用 `| tail` 收尾导致退出码失真（管道退出码取最后一个命令），已用 pipefail 重跑并以 reactor 摘要为准，上述结果可信。
