# Task 1 评审报告 — Phase 0+1：依赖配置 + OSS 存储层

> 评审日期：2026-07-27
> 评审人：任务评审 subagent（只读评审，未修改任何文件、未执行 git 写操作）
> 评审输入：计划 `docs/plan/oss-storage-and-file-tools-plan.md` §2/§4 Phase 0-1/§5、实现者报告 `task-1-report.md`、改动 `task-1-diff.txt`、全部新增源文件直读、git 工作区状态

## 1. 规格符合性（逐条核对绑定约束）

| # | 约束 | 结论 | 依据 |
|---|---|---|---|
| 1 | `FileUploadService` 三方法 + `getFilePath` 删除 | ✅ | `FileUploadService.java:11-21`：`uploadFile(Long, MultipartFile)`、`getOwnedFile(Long fileId, Long userId)`、`openStream(UploadedFile)`；`getFilePath` 已删。实现 `FileUploadServiceImpl.java:68-77`：`getById` 为 null 抛 `BusinessException(404)`，非属主抛 `BusinessException(403)`（`file.getUserId().equals(userId)`，user_id 为 DB NOT NULL 列，无 NPE） |
| 2 | OSS key 格式 `{dirPrefix}{userId}/{UUID}.{ext}`；流式上传不落盘 | ✅ | `FileUploadServiceImpl.java:44-54`：key 拼接一致且 dirPrefix 自动补 `/`；try-with-resources 包 `file.getInputStream()` 直接 `ossService.upload`，无任何本地盘写入 |
| 3 | 上传校验：扩展名白名单 + 20MB + TrialGuard 保留 + 空文件校验 | ✅ | `uploadFile` 顺序：TrialGuard（首行保留）→ `file.isEmpty()` → `FileTypes.isAllowed`（拒绝时文案列出允许类型）→ 20MB。`FileTypes.java`：DOC=xlsx/xls/csv/docx/pdf/md、IMAGE=png/jpg/jpeg/webp/bmp，与计划 §4 Phase 0.5 完全一致；`getExtension` null 安全 + 小写化，`isAllowed(null)` 返回 false 不 NPE。`application.yml` 既有 multipart 上限 50MB，service 层 20MB 校验实际可达 |
| 4 | `storedPath` → `ossKey`；schema.sql `oss_key VARCHAR(512) NOT NULL` | ✅ | `UploadedFile.java:19`；`schema.sql:62`。全库 grep 确认 Java/SQL 源码无 `storedPath/stored_path` 残留（仅历史 docs，不属本阶段） |
| 5 | `FileController.upload` 返回 `R<UploadedFileVO>`，不含 ossKey | ✅ | `FileController.java:19-21`；`UploadedFileVO` 仅 id/originalName/fileSize/contentType/createdAt（连 userId 都不带），静态工厂 `from()` |
| 6 | application.yml 删 `file-upload-dir`、新增 `db-genius.oss.*`（5 项）与 `db-genius.ocr.enabled` | ✅ | `application.yml:62-69`，yaml 与计划 Phase 0 第 3 条逐字一致（含 dir-prefix 默认 `uploads/`、ocr.enabled 默认 false） |
| 7 | 依赖：aliyun-sdk-oss 3.17.4（service）、pdfbox 3.0.3 + poi-ooxml 5.2.5（agent），根 pom dependencyManagement 管理 | ✅ | 根 `pom.xml` properties 三版本 + dependencyManagement 三条目（poi 注释注明显式 pin 对齐 EasyExcel）；`db-genius-service/pom.xml` +oss；`db-genius-agent/pom.xml` +pdfbox/poi-ooxml（无 version，受根管理） |
| 8 | 配置缺失时启动 fail-fast | ✅ | 落在 `OssConfig.@PostConstruct validate()`（而非 OssServiceImpl）：endpoint/bucket/accessKeyId/accessKeySecret 逐项检查，缺失抛 `IllegalStateException` 且文案列出对应环境变量名。`@PostConstruct` 先于 `@Bean ossClient()` 执行，client 不会被构建，fail-fast 有效。约束字面写"OssServiceImpl"，实际放在配置类上语义更准，判符合 |
| 9 | `.env.example` 与 `deploy/.env.example` 同步新变量 | ✅ | 两份均删 `DB_GENIUS_FILE_DIR`，新增 `ALIYUN_OSS_ENDPOINT/BUCKET/ACCESS_KEY_ID/ACCESS_KEY_SECRET/DIR_PREFIX/OCR_ENABLED` 共 6 项，带中文注释（deploy 版含"必填/可选"说明） |
| 10 | agent 模块 `WorkflowHandler` 编译失败属预期，不算缺陷 | ✅（确认状态属实） | `WorkflowHandler.java:74` 仍调 `getFilePath`，未做任何临时改动；agent pom 仅加依赖。与报告声明的编译现象一致 |
| 11 | 无多余产物 | ✅ | `git status`：11 个 M + 新增类恰为报告所列（FileTypes/UploadedFileVO/OssService/OssServiceImpl/config 下 2 类），无计划外类、配置或重构（`.superpowers/`、`docs/plan/` 等为流程产物） |

**规格结论：11/11 全部 ✅，无 ❌。**

## 2. 代码质量发现

### Critical
无。

### Important
无。

### Minor

1. **OSS SDK 异常未包装，错误语义丢失** — `FileUploadServiceImpl.java:50-54`
   `catch (IOException)` 只能捕获 `file.getInputStream()` 的异常；`ossService.upload` 抛出的 `OSSException`/`ClientException`（均为 RuntimeException）直接穿透至 `GlobalExceptionHandler` 的兜底 `Exception` 处理器，客户端得到 "Internal server error" 而非 "File upload failed"。功能正确性不受影响（DB 落库在 upload 之后，失败无脏行；服务端有完整堆栈日志），但 OSS 鉴权/网络/bucket 错误是运维期高频问题，建议 catch `OSSException | ClientException` 包装为 `BusinessException("File upload failed: ...")`。

2. **OSS 上传成功但 DB 落库失败 → 孤儿对象** — `FileUploadServiceImpl.java:56-62`
   `save()` 失败时已上传的 OSS 对象无补偿 `delete(ossKey)`。发生率低、计划未要求，列为 Minor；建议在 save 外包 try/catch 做补偿删除，或接受泄漏由后续清理任务处理。

3. **docker-compose.yml 部署残留** — `docker-compose.yml:20,23,44`
   仍注入 `DB_GENIUS_FILE_DIR: /app/uploads` 且挂载 `uploads` volume。已失效、运行时无害（Spring 忽略多余环境变量），报告已自列为遗留项。绑定约束未要求本阶段改 compose，不计规格不符；建议 Task 3/4 换成 OSS/OCR 变量透传时一并删除 volume。

### 针对评审关注点的专项确认（均通过）

- **OSS client 生命周期**：`OssConfig.java:43` `@Bean(destroyMethod = "shutdown")` —— 容器关闭时 shutdown client。未用 `@PreDestroy`，但 client 由 config 创建、destroyMethod 是等价且更归属正确的做法。✅
- **InputStream 生命周期**：上传侧 try-with-resources 关闭 `MultipartFile` 流 ✅；下载侧 `OssServiceImpl.download` 返回 `getObject().getObjectContent()`，接口与 `openStream` javadoc 均注明"调用方负责关闭"，关闭该流即释放连接，做法标准 ✅（后续 Task 2/3 的 parser 必须用 try-with-resources 消费，见 ⚠️-2）。
- **contentLength/contentType 传递**：`OssServiceImpl.upload:21-25` 用 `file.getSize()` 实际大小 `setContentLength`（避免 SDK 走 chunked/缓冲），contentType 非空才设置 ✅。
- **并发安全**：`OssServiceImpl`/`OssProperties`/`FileTypes` 均无可变共享状态；aliyun `OSS` client 线程安全 ✅。
- **风格一致性**：`OssProperties` 逐字段注释 + `@Component @ConfigurationProperties @Getter/@Setter` 与 `TrialProperties` 完全同构；`FileTypes` 不可变 `Set.of` + 私有构造；异常一律 `BusinessException`。与项目现有风格一致 ✅。

## 3. ⚠️ 待确认项

1. **编译验证未由本评审复跑**：报告声明 common/model/service `BUILD SUCCESS`、agent 仅 `WorkflowHandler` 一处预期失败。我只读评审未重新执行 Maven 构建（耗时且会写 target/）；静态核对代码状态与报告声明一致，接口签名、导入、Lombok 注解均自洽，无明显编译隐患。
2. **`openStream`/`OssService.download` 目前无调用方**：关闭契约靠 Task 2/3 的 parser 落实，届时需复查消费方均用 try-with-resources，否则 OSS 连接泄漏。同理 `OssService.delete` 暂无调用方（计划内，后续删除端点使用）。
3. **404/403 的呈现层级**：`GlobalExceptionHandler` 返回 `R.fail(code, msg)`，HTTP 状态码恒 200，404/403 体现在 body code —— 与项目既有 `TrialBusinessException(403)` 机制一致，判定符合约束语义；若验收口径要求真实 HTTP 状态码则需另议（那将是全局既有行为，非本任务引入）。
4. **文档残留不属本期范围**：`README.md:282`（`DB_GENIUS_FILE_DIR`）、`api-docs.yaml:987`（`storedPath`）未更新 —— 计划明确归属 Phase 5，报告已列遗留，确认无遗漏风险。

## 4. 结论

- **规格符合性：✅ 通过（11/11）**，无不符合项。
- **代码质量：Approved**，无需修复的 Critical/Important 项；3 个 Minor（SDK 异常包装、孤儿对象补偿、compose 残留），均不阻塞进入 Task 2，建议在后续任务中顺手处理 Minor-1/2，Minor-3 随 compose 变量透传一起改。
