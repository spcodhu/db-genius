# Task 1 报告 — Phase 0+1：依赖配置 + OSS 存储层

日期：2026-07-27
计划来源：`docs/plan/oss-storage-and-file-tools-plan.md` §2 / §4 Phase 0-1 / §5，按主 agent 裁定 1-9 执行。
约束遵守：全程无任何 git 操作，仅改工作区文件；未改 agent 模块任何代码。

## 逐文件变更要点

### 新增

- `db-genius-model/src/main/java/com/dbgenius/model/constant/FileTypes.java`
  - 文件类型白名单集中定义（裁定 1）：`DOC_EXTENSIONS`（xlsx/xls/csv/docx/pdf/md）、`IMAGE_EXTENSIONS`（png/jpg/jpeg/webp/bmp）均为 `Set.of` 不可变集；`MAX_FILE_SIZE = 20MB`；辅助方法 `getExtension / isDocument / isImage / isAllowed`。
- `db-genius-model/src/main/java/com/dbgenius/model/vo/UploadedFileVO.java`
  - 上传返回 VO（裁定 2）：id / originalName / fileSize / contentType / createdAt，含静态工厂 `from(UploadedFile)`，不含 ossKey。
- `db-genius-service/src/main/java/com/dbgenius/service/OssService.java` — OSS 接口（裁定 3）。
- `db-genius-service/src/main/java/com/dbgenius/service/impl/OssServiceImpl.java`
  - 注入 `OSS` client + `OssProperties`；upload 用 `ObjectMetadata` 设置 contentLength/contentType 后 `putObject`；download 返回 `getObject(...).getObjectContent()`；delete 调 `deleteObject`。
- `db-genius-service/src/main/java/com/dbgenius/service/config/OssProperties.java`
  - `@Component @ConfigurationProperties(prefix = "db-genius.oss")`，风格对齐 `TrialProperties`；字段 endpoint/bucket/accessKeyId/accessKeySecret/dirPrefix（默认 `uploads/`）。
- `db-genius-service/src/main/java/com/dbgenius/service/config/OssConfig.java`
  - `@PostConstruct` fail-fast 校验：endpoint/bucket/accessKeyId/accessKeySecret 任一为空则抛 `IllegalStateException`，异常文案逐项列出缺失配置及对应环境变量名（ALIYUN_OSS_ENDPOINT 等）；`@Bean(destroyMethod = "shutdown")` 构建 `OSS` client。

### 修改

- `db-genius-model/.../entity/UploadedFile.java`：`storedPath` → `ossKey`（裁定 5；`map-underscore-to-camel-case` 自动映射 `oss_key`）。
- `db-genius-service/.../FileUploadService.java`：接口按裁定 4 重写（签名见下）。
- `db-genius-service/.../impl/FileUploadServiceImpl.java`：
  - 删除本地磁盘逻辑与 `@Value("${db-genius.file-upload-dir}")`；
  - `uploadFile`：TrialGuard 保留 → 空文件校验 → `FileTypes.isAllowed` 扩展名白名单（不合法抛 BusinessException 并列出允许类型）→ 20MB 大小校验 → key = `{dirPrefix}{userId}/{UUID}.{ext}`（dirPrefix 自动补 `/`）→ try-with-resources 流式 `ossService.upload`，不落盘 → 实体存 `ossKey` 落库；
  - 新增 `getOwnedFile`：不存在 404 / 非属主 403；新增 `openStream` 委托 `ossService.download(file.getOssKey())`；删除 `getFilePath`。
- `db-genius-web/.../controller/FileController.java`：`upload` 改返回 `R<UploadedFileVO>`（裁定 2）。
- `db-genius-web/src/main/resources/application.yml`：删除 `db-genius.file-upload-dir`；新增 `db-genius.oss`（5 项，环境变量占位）与 `db-genius.ocr.enabled`，yaml 照抄计划 Phase 0 第 3 条（裁定 6）。
- `db-genius-web/src/main/resources/db/schema.sql`：`uploaded_file.stored_path TEXT` → `oss_key VARCHAR(512) NOT NULL`（裁定 5）。
- `.env.example`、`deploy/.env.example`：删除 `DB_GENIUS_FILE_DIR`；新增 `ALIYUN_OSS_ENDPOINT / ALIYUN_OSS_BUCKET / ALIYUN_ACCESS_KEY_ID / ALIYUN_ACCESS_KEY_SECRET / ALIYUN_OSS_DIR_PREFIX / ALIYUN_OCR_ENABLED`，带注释（裁定 7）。
- 根 `pom.xml`：properties 新增 `aliyun-oss.version=3.17.4`、`pdfbox.version=3.0.3`、`poi.version=5.2.5`；`dependencyManagement` 新增 `com.aliyun.oss:aliyun-sdk-oss`、`org.apache.pdfbox:pdfbox`、`org.apache.poi:poi-ooxml`（裁定 8，poi 注释注明显式 pin 对齐 EasyExcel 传递版本）。
- `db-genius-service/pom.xml`：+ `aliyun-sdk-oss`；`db-genius-agent/pom.xml`：+ `pdfbox`、`poi-ooxml`（仅加依赖，tool 代码属 Task 2）。

## 接口最终签名

```java
// com.dbgenius.service.FileUploadService
public interface FileUploadService extends IService<UploadedFile> {
    UploadedFile uploadFile(Long userId, MultipartFile file);
    UploadedFile getOwnedFile(Long fileId, Long userId);   // 404 不存在 / 403 非属主
    InputStream openStream(UploadedFile file);
}

// com.dbgenius.service.OssService
public interface OssService {
    String upload(String key, InputStream in, long contentLength, String contentType);
    InputStream download(String key);
    void delete(String key);
}

// com.dbgenius.model.constant.FileTypes
public final class FileTypes {
    public static final Set<String> DOC_EXTENSIONS;    // xlsx,xls,csv,docx,pdf,md
    public static final Set<String> IMAGE_EXTENSIONS;  // png,jpg,jpeg,webp,bmp
    public static final long MAX_FILE_SIZE;            // 20L * 1024 * 1024
    public static String getExtension(String filename);
    public static boolean isDocument(String filename);
    public static boolean isImage(String filename);
    public static boolean isAllowed(String filename);
}

// com.dbgenius.model.vo.UploadedFileVO
public class UploadedFileVO {  // @Data
    private Long id;
    private String originalName;
    private Long fileSize;
    private String contentType;
    private LocalDateTime createdAt;
    public static UploadedFileVO from(UploadedFile file);
}
```

## 编译验证

环境：`export JAVA_HOME="D:\Java\java21"`（git-bash，Windows）。

1. `./mvnw clean compile -DskipTests -pl db-genius-common,db-genius-model,db-genius-service -am`
   → **BUILD SUCCESS**（common/model/service 全过，23s）。
2. `./mvnw clean compile -DskipTests -pl db-genius-common,db-genius-model,db-genius-service,db-genius-agent -am`
   → common/model/service SUCCESS；**agent FAILURE，唯一错误为预期项**：
   `WorkflowHandler.java:[74,26] 找不到符号：方法 getFilePath()，位置：接口 com.dbgenius.service.FileUploadService`。
   即 agent 模块在解析完新依赖（pdfbox/poi-ooxml 正常下载）后，仅因裁定 4 中说明的 `getFilePath` 删除而失败，由 Task 3（Phase 4 接线）修复。未对 WorkflowHandler 做任何临时改动（遵守"本任务不要改 agent 模块"）。

## 遇到的坑

- 无实质坑。两点说明：
  - 编译错误信息在 git-bash 控制台显示为乱码（javac 中文消息 + GBK 控制台），不影响判断，错误位置与符号清晰可读。
  - `aliyun-sdk-oss:3.17.4` 传递依赖（httpclient、jdom2 等）首次下载较慢，但均正常解析，与现有依赖无版本冲突。

## 遗留问题（交后续任务）

- `WorkflowHandler.java:73-76` 编译失败 —— 预期，Task 3 修复（改 fileId 逻辑引用 + toolContext）。
- `docker-compose.yml:20` 仍注入 `DB_GENIUS_FILE_DIR`（已失效但不报错），建议 Task 3/4 顺手换成 OSS/OCR 变量透传。
- `README.md:282` 环境变量表仍列 `DB_GENIUS_FILE_DIR`；`api-docs.yaml:987` 仍有 `storedPath` 字段 —— 属 Phase 5 文档收尾范围。
- OCR 仅加了 `db-genius.ocr.enabled` 配置项，无代码（按裁定 8）。
