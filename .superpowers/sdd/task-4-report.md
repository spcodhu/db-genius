# Task 4 报告：OSS 存储 + 文件 Tools 改造 Phase 5 收尾（单测 / 文档配置 / 全量验证）

> 执行日期：2026-07-27
> 约束遵守：全程零 git 操作，仅改工作区文件。

## 1. 新增单测

测试依赖：两模块均已有 `spring-boot-starter-test`（test scope，含 JUnit 5 + Mockito），**无需改 pom**。风格对齐现有 `SqlExecuteToolTest` / `TrialGuardTest`（纯 JUnit + Mockito，无 Spring 容器）。

### 1.1 `db-genius-agent/src/test/java/com/dbgenius/agent/tool/FileReadToolTest.java`（5 个用例）

仅 mock `FileUploadService`，使用**真实 `FileAccessGuard`** 以覆盖完整校验链路；`ToolContext` 直接 `new ToolContext(Map.of(...))` 构造。

| 用例 | 验证点 |
|---|---|
| `shouldRejectFileIdNotInAllowedList` | fileId ∉ allowedFileIds → `success:false` + "not among the files referenced"，不抛异常，且不调 `getOwnedFile` |
| `shouldRejectWhenSecurityContextMissing` | ToolContext 缺 userId/allowedFileIds（含整个为 null）→ `success:false` + "missing security context" |
| `shouldRejectWhenOwnershipCheckFails` | 白名单通过但 `getOwnedFile` 抛 `BusinessException(403)` → `success:false` 越权文本 |
| `shouldReadMarkdownFileSuccessfully` | mock md 文件 + 小 InputStream → `success:true`、`format:md`、content 正确、`truncated:false` |
| `shouldRejectUnsupportedExtension` | `.zip` → `success:false` + "Unsupported file type"，且不调 `openStream` |

结果：`Tests run: 5, Failures: 0, Errors: 0`

### 1.2 `db-genius-service/src/test/java/com/dbgenius/service/FileUploadServiceImplTest.java`（3 个用例）

mock `TrialGuard` 与 `OssService`，`OssProperties` 用真实实例。

| 用例 | 验证点 |
|---|---|
| `shouldRejectExecutableFile` | `.exe` 抛 `BusinessException("Unsupported file type...")` |
| `shouldRejectLegacyDocFile` | `.doc` 老格式明确不支持，抛 `BusinessException` |
| `shouldRejectFileOver20MB` | 大小 > `FileTypes.MAX_FILE_SIZE`（20MB）抛 `BusinessException("File too large, max 20MB")` |

三个用例均 `verifyNoInteractions(ossService)`，确认校验发生在 OSS 上传之前。

结果：`Tests run: 3, Failures: 0, Errors: 0`

## 2. 文档与配置改动清单

| 文件 | 改动 |
|---|---|
| `AGENTS.md` | Conventions & Security 新增一条：文件存储为阿里云 OSS（`db-genius.oss.*`）、`uploaded_file` 存 `oss_key`、tool 为 `FileReadTool`/`ImageReadTool`（`ExcelParseTool` 已删）、fileId + ToolContext（`FileAccessGuard`）安全模型。原文件本无 `file-upload-dir` 描述，无需删除 |
| `README.md` | 环境变量表删 `DB_GENIUS_FILE_DIR`，补 `ALIYUN_OSS_ENDPOINT/BUCKET/ACCESS_KEY_ID/ACCESS_KEY_SECRET/OSS_DIR_PREFIX/OCR_ENABLED/OCR_ENDPOINT` 共 7 行 |
| `api-docs.yaml` | `UploadedFile` schema 对齐 `UploadedFileVO`：删 `userId` 与 `storedPath`，保留 id/originalName/fileSize/contentType/createdAt，加"不含 OSS 存储 key"说明 |
| `docker-compose.yml` | 删 `DB_GENIUS_FILE_DIR: /app/uploads` 注入与 `uploads` volume（含顶层 volumes 声明），补 OSS/OCR 共 8 个环境变量透传，写法对齐现有 `SPRING_DATASOURCE_*`（必填裸 `${VAR}`、可选项带 `:-` 默认值） |
| `db-genius-web/src/main/resources/application.yml` | `db-genius.ocr` 下补 `endpoint: ${ALIYUN_OCR_ENDPOINT:ocr.cn-hangzhou.aliyuncs.com}` |
| `.env.example`、`deploy/.env.example` | 各补 `ALIYUN_OCR_ENDPOINT=ocr.cn-hangzhou.aliyuncs.com`（带注释） |
| `docs/plan/oss-storage-and-file-tools-plan.md` | 开头状态「待执行」→「已执行（2026-07-27）」 |

**OCR endpoint 取值说明**：任务描述提到默认 `ocr-api.cn-hangzhou.aliyuncs.com`，但 `OcrConfig.java:21` 代码实际 `@Value` 默认值为 `ocr.cn-hangzhou.aliyuncs.com`，按要求"以代码为准对齐"，yml / env / compose / README 统一采用代码中的默认值，未改代码。

全仓 grep `DB_GENIUS_FILE_DIR|file-upload-dir` 确认：活跃配置/代码中已无残留，剩余命中均在历史文档（plan 的"现状"章节、`.superpowers/sdd/` 历史报告、`docs/exectue-plan/rag-pgvector-implementation.md` 另一计划文档），不属本次范围。

## 3. 全量验证

环境：`export JAVA_HOME="D:\Java\java21"`，Windows git-bash。

1. `./mvnw clean compile -DskipTests` → **BUILD SUCCESS**，6 个模块全部 SUCCESS（Total time: 48.865 s）。
2. `./mvnw test` → **BUILD SUCCESS**，全仓库测试通过：
   - common：8/8；model：5/5；service：49/49（含新增 `FileUploadServiceImplTest` 3/3）；agent：14/14（含新增 `FileReadToolTest` 5/5）。
   - 无失败、无跳过。日志中 `SqlExecuteToolTest` 期间的两条 ERROR 输出是该测试**故意触发** SQL/AES 失败路径的预期日志（改造前就如此），非测试失败。

## 4. 遗留问题

- 手动冒烟（真实 OSS 上传 / OCR / 越权诱导提问，plan §6.3）需真实环境变量与阿里云资源，本任务未执行。
- Task 1 review 的两条 minor finding（`uploadFile` 未包装 `OSSException/ClientException`、OSS 上传成功后 `save()` 失败无补偿删除）仍未处理，不属 Phase 5 范围，建议另立任务。

## 修复轮次 1：OCR endpoint 默认值错误（终审 blocker）

**问题**：`ocr_api20210707` SDK（RecognizeAdvanced）的官方接入点是 `ocr-api.cn-hangzhou.aliyuncs.com`，而代码与配置默认值写成了旧版产品（API 2019-12-30）的 `ocr.cn-hangzhou.aliyuncs.com`，按默认配置开启 OCR 必然调用失败。

**修改文件清单**（统一 `ocr.cn-hangzhou` → `ocr-api.cn-hangzhou`，共 6 个文件 8 处，含注释/文档说明行）：

| 文件 | 位置 |
|---|---|
| `db-genius-agent/src/main/java/com/dbgenius/agent/ocr/OcrConfig.java` | `@Value` 默认值（代码侧源头） |
| `db-genius-web/src/main/resources/application.yml` | `db-genius.ocr.endpoint` 占位默认值 |
| `.env.example`、`deploy/.env.example` | `ALIYUN_OCR_ENDPOINT` 值及注释（各 2 处） |
| `docker-compose.yml` | `ALIYUN_OCR_ENDPOINT` 透传默认值 |
| `README.md` | 环境变量表 `ALIYUN_OCR_ENDPOINT` 默认值说明 |

**验证**：改后全仓 grep 确认活跃代码/配置无 `ocr.cn-hangzhou` 残留（剩余命中均为 `.superpowers/sdd/` 历史报告与本报告上文的当时记录）；`export JAVA_HOME="D:\Java\java21" && ./mvnw test` → **BUILD SUCCESS**，全仓 85 个测试 0 失败（agent 14/14 含 FileReadToolTest 5/5，service 49/49 含 FileUploadServiceImplTest 3/3）。
