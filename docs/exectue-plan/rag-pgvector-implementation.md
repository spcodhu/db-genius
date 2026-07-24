# RAG + pgvector 实施计划

> 面向开发者/运维，列出为 DB-Genius 引入基于 pgvector 的 RAG 技术栈所需的所有操作。
> 重点在需要**手动配置/操作**的部分（服务器环境、Docker 配置、依赖变更），代码变动仅作提纲式介绍。

---

## 一、设计思路

### 1.1 doc_content 与 RAG 知识库：两个独立体系

| 维度 | doc_content（现有，不改动） | RAG 知识库（新增独立模块） |
|------|---------------------------|--------------------------|
| 来源 | 连接 MySQL 后自动扫描 information_schema 生成 | 用户手动上传文档（Excel / Markdown / Word 等） |
| 存储 | db_config.doc_content 字段（PostgreSQL TEXT） | knowledge_file 表（元信息）+ knowledge_document 表（pgvector 向量表） |
| 注入方式 | **每次对话直接拼入 systemPrompt**，AI 严格参考 | **Agent 自主调用 tool 检索**，按需补充上下文 |
| 数据隔离 | 天然隔离：每个 db_config 仅属于一个 user | user_id 字段隔离，检索时过滤 |
| 用途 | 保证 SQL 生成的表/字段名精确无误 | 补充业务语义、设计文档、规范等 doc_content 不包含的信息 |

**核心原则**：doc_content 保持现有流程不动，不走向量检索。RAG 知识库是全新的、独立的知识库模块。

### 1.2 RAG 的两大使用场景

#### 场景 A：辅助决策（Agent 自主调用）

用户在 SQL 查询 / 数据库对比 / 工作流对话中，Agent 思考时发现 doc_content 信息不足以回答用户问题（比如用户问「这个 status 字段的业务含义是什么」但表注释里没写），**主动调用** searchKnowledge tool 从知识库检索补充信息，拼入对话上下文。

**触发条件**：Agent 自主判断，不是每次对话都触发。

#### 场景 B：纯知识库对话（用户意图驱动）

用户上传了设计文档、业务规范、技术方案等，直接对着知识库提问，不连数据库。

**交互方式**：用户说「帮我查一下上个月写的数据库设计规范」→ IntentClassifier 识别为知识库查询意图 → Agent 不连数据库，纯走 RAG 检索 → 回答。

#### 场景 C：doc_content 可选加入知识库

用户可手动将某个 db_config 的 doc_content 导入知识库，使其在场景 B（纯知识对话）中也能被检索到。这是一个可选手动操作，不是自动行为。

---

## 二、需要手动配置的部分（运维侧）

### 2.1 PostgreSQL 服务器：安装 pgvector 扩展

**前提**：你的 PostgreSQL 服务器（运行 db_genius 数据库的那台）需要安装 pgvector 扩展。

#### 步骤 1：确认 PostgreSQL 版本

```bash
psql -U postgres -c "SELECT version();"
```

pgvector 要求 PostgreSQL >= 12。

#### 步骤 2：安装 pgvector

**方式 A：使用包管理器（推荐，Ubuntu/Debian）**

```bash
# Ubuntu 22.04+，版本号改为你实际的 PG 大版本，如 14/15/16
sudo apt update
sudo apt install -y postgresql-16-pgvector
```

**方式 B：从源码编译（通用）**

```bash
sudo apt install -y postgresql-server-dev-16 git make gcc
cd /tmp
git clone --branch v0.7.4 https://github.com/pgvector/pgvector.git
cd pgvector
make
sudo make install
```

**方式 C：Docker PostgreSQL 镜像自带 pgvector**

如果你用的是 pgvector/pgvector:pg16 镜像，扩展已预装，跳过此步。
当前项目 PostgreSQL 是远程独立部署的，需要按 A 或 B 安装。

#### 步骤 3：在 db_genius 数据库中启用扩展

```bash
psql -U postgres -d db_genius -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

验证：

```bash
psql -U postgres -d db_genius -c "SELECT * FROM pg_extension WHERE extname='vector';"
```

预期看到一条 vector 的记录。

#### 步骤 4：创建知识库相关表（建表 SQL）

**4a. 知识库文件元信息表（knowledge_file）**

```sql
-- 在 app schema 下创建知识库文件表
SET search_path TO app;

CREATE TABLE IF NOT EXISTS knowledge_file (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    original_name VARCHAR(256) NOT NULL,         -- 原始文件名
    stored_path TEXT NOT NULL,                    -- 服务器存储路径（用于下载）
    file_size BIGINT,                             -- 文件大小（字节）
    content_type VARCHAR(128),                    -- MIME 类型
    file_type VARCHAR(32) NOT NULL DEFAULT 'text', -- 文件类型：text/markdown/excel/word/pdf
    status SMALLINT NOT NULL DEFAULT 1,           -- 1=正常 0=已删除（软删除）
    chunk_count INT NOT NULL DEFAULT 0,           -- 向量化后分块数量
    embedded_at TIMESTAMP,                        -- 向量化完成时间
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE knowledge_file IS '知识库文件元信息表 - 追踪用户上传的原始文件';
COMMENT ON COLUMN knowledge_file.status IS '1=正常, 0=已软删除';
COMMENT ON COLUMN knowledge_file.chunk_count IS '该文件向量化后的分块总数';
COMMENT ON COLUMN knowledge_file.embedded_at IS '向量化完成时间，NULL 表示未向量化或处理中';

CREATE INDEX IF NOT EXISTS idx_kf_user_id ON knowledge_file(user_id);
CREATE INDEX IF NOT EXISTS idx_kf_user_status ON knowledge_file(user_id, status);
```

**4b. 知识库向量表（knowledge_document）**

```sql
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    file_id BIGINT REFERENCES knowledge_file(id) ON DELETE SET NULL,  -- 关联知识库文件
    title VARCHAR(512) NOT NULL,
    content TEXT NOT NULL,
    content_type VARCHAR(32) NOT NULL DEFAULT 'text',
    source_file_name VARCHAR(256),
    chunk_index INT NOT NULL DEFAULT 0,
    embedding vector(1536),
    metadata JSONB DEFAULT '{}',
    status SMALLINT NOT NULL DEFAULT 1,           -- 1=正常 0=已软删除（跟随文件软删除）
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE knowledge_document IS 'RAG 知识库向量表 - 文档分块+向量化结果';
COMMENT ON COLUMN knowledge_document.file_id IS '关联 knowledge_file.id，导入自 doc_content 时为 NULL';
COMMENT ON COLUMN knowledge_document.user_id IS '多租户隔离：所属用户ID';
COMMENT ON COLUMN knowledge_document.content_type IS 'text=通用文档, table_doc=从doc_content导入的表结构';
COMMENT ON COLUMN knowledge_document.embedding IS '向量化后的文本表示，维度需与 Embedding 模型输出匹配';
COMMENT ON COLUMN knowledge_document.status IS '1=正常, 0=已软删除，检索时过滤 status=0 的记录';

CREATE INDEX IF NOT EXISTS idx_kd_user_id ON knowledge_document(user_id);
CREATE INDEX IF NOT EXISTS idx_kd_user_status ON knowledge_document(user_id, status);
CREATE INDEX IF NOT EXISTS idx_kd_file_id ON knowledge_document(file_id);
CREATE INDEX IF NOT EXISTS idx_kd_user_content_type ON knowledge_document(user_id, content_type);
```

**重要**：向量维度 vector(1536) 需要和实际使用的 Embedding 模型输出维度匹配。

### 2.2 Docker Compose 变更

当前 docker-compose.yml 中未包含 PostgreSQL（因为是远程部署），**无需在 compose 中加 PostgreSQL 容器**。

需在 app 服务的环境变量中新增 Embedding/RAG 配置：

#### .env.example、deploy/.env.example 追加内容

```bash
# ==================== RAG / Embedding 配置 ====================
# Embedding 模型配置（独立于 Chat 模型，因 DeepSeek 可能不提供 Embedding API）
SPRING_AI_OPENAI_EMBEDDING_ENABLED=true
SPRING_AI_OPENAI_EMBEDDING_API_KEY=sk-your-embedding-api-key
SPRING_AI_OPENAI_EMBEDDING_BASE_URL=https://api.openai.com
SPRING_AI_OPENAI_EMBEDDING_MODEL=text-embedding-3-small
SPRING_AI_OPENAI_EMBEDDING_DIMENSION=1536

# RAG 检索参数
DB_GENIUS_RAG_TOP_K=5
DB_GENIUS_RAG_SIMILARITY_THRESHOLD=0.7
DB_GENIUS_RAG_CHUNK_SIZE=1000
DB_GENIUS_RAG_CHUNK_OVERLAP=200
```

#### docker-compose.yml 中 app 服务追加环境变量

```yaml
services:
  app:
    environment:
      # ... 原有环境变量保持不变 ...
      # RAG / Embedding 配置
      SPRING_AI_OPENAI_EMBEDDING_ENABLED: ${SPRING_AI_OPENAI_EMBEDDING_ENABLED:-false}
      SPRING_AI_OPENAI_EMBEDDING_API_KEY: ${SPRING_AI_OPENAI_EMBEDDING_API_KEY}
      SPRING_AI_OPENAI_EMBEDDING_BASE_URL: ${SPRING_AI_OPENAI_EMBEDDING_BASE_URL}
      SPRING_AI_OPENAI_EMBEDDING_MODEL: ${SPRING_AI_OPENAI_EMBEDDING_MODEL}
      SPRING_AI_OPENAI_EMBEDDING_DIMENSION: ${SPRING_AI_OPENAI_EMBEDDING_DIMENSION}
      DB_GENIUS_RAG_TOP_K: ${DB_GENIUS_RAG_TOP_K:-5}
      DB_GENIUS_RAG_SIMILARITY_THRESHOLD: ${DB_GENIUS_RAG_SIMILARITY_THRESHOLD:-0.7}
      DB_GENIUS_RAG_CHUNK_SIZE: ${DB_GENIUS_RAG_CHUNK_SIZE:-1000}
      DB_GENIUS_RAG_CHUNK_OVERLAP: ${DB_GENIUS_RAG_CHUNK_OVERLAP:-200}
```

### 2.3 PostgreSQL 连接配置检查清单

| 检查项 | 说明 |
|--------|------|
| pgvector 扩展已安装 | CREATE EXTENSION IF NOT EXISTS vector; 已执行 |
| 向量表已建在 app schema 下 | knowledge_document 表存在，含 user_id 字段 |
| JDBC URL 正确 | 已包含 ?currentSchema=app，应用能访问 knowledge_document |
| 防火墙放行 | 应用服务器能连通 PostgreSQL 5432 端口 |
| 账号权限 | 应用的 PostgreSQL 账号有建表权限（或 DBA 已手动建好向量表） |

### 2.4 服务器依赖变更

**无需额外安装系统依赖**。所有变更都在 PostgreSQL 服务端和应用代码层：

| 环境 | 操作 |
|------|------|
| PostgreSQL 服务器 | 一次性安装 pgvector 扩展（系统级操作） |
| 应用服务器 | 无新系统依赖（pgvector 通过 JDBC 驱动通信） |
| Docker 镜像 | Maven 依赖增加 spring-ai-pgvector-store |

### 2.5 文档解析依赖（文件上传到知识库）

用户上传 Word (.docx) 时需要解析文本，建议在应用层处理：

| 文件类型 | 解析方式 | 额外 Maven 依赖 |
|----------|----------|----------------|
| .txt / .md | 直接读取文本 | 无 |
| .xlsx / .xls | Apache POI（项目已有 easyexcel，可复用或加 poi）| org.apache.poi:poi-ooxml |
| .docx | Apache POI | org.apache.poi:poi-ooxml |
| .pdf | PDFBox | org.apache.pdfbox:pdfbox |

> 文档解析是可选模块，可以先支持 .txt/.md/.xlsx，Word/PDF 后续再加。

---

## 三、代码变动（提纲式介绍）

### 3.1 Maven 依赖变更

#### 父 POM（pom.xml）—— 版本管理新增

```xml
<properties>
    <spring-ai-pgvector.version>1.0.0-M4</spring-ai-pgvector.version>
    <apache-poi.version>5.3.0</apache-poi.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- 新增：Spring AI pgvector store -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-pgvector-store</artifactId>
            <version>${spring-ai-pgvector.version}</version>
        </dependency>
        <!-- 新增：文档解析（可选） -->
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
            <version>${apache-poi.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

#### db-genius-service/pom.xml —— 实际引用

```xml
<!-- 新增：pgvector 向量存储 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store</artifactId>
</dependency>

<!-- 新增：文档解析引擎（可选，按需引入） -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
</dependency>
```

### 3.2 新增 Entity（db-genius-model）

#### 3.2.1 KnowledgeFile：知识库文件元信息

位置：db-genius-model/src/main/java/com/dbgenius/model/entity/KnowledgeFile.java

```java
@Data
@TableName("knowledge_file")
public class KnowledgeFile {
    private Long id;
    private Long userId;
    private String originalName;     // 原始文件名
    private String storedPath;       // 服务器存储路径（用于下载）
    private Long fileSize;           // 文件大小（字节）
    private String contentType;      // MIME 类型
    private String fileType;         // text / markdown / excel / word / pdf
    private Integer status;          // 1=正常 0=已软删除
    private Integer chunkCount;      // 向量化后的分块数量
    private LocalDateTime embeddedAt; // 向量化完成时间
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 扩展字段（非数据库）
    @TableField(exist = false)
    private String downloadUrl;      // 下载链接（由 Controller 动态拼接）
}
```

#### 3.2.2 KnowledgeDocument：向量分块

位置：db-genius-model/src/main/java/com/dbgenius/model/entity/KnowledgeDocument.java

```java
@Data
@TableName("knowledge_document")
public class KnowledgeDocument {
    private Long id;
    private Long userId;            // 多租户隔离
    private Long fileId;            // 关联 knowledge_file.id，从 doc_content 导入时为 NULL
    private String title;
    private String content;
    private String contentType;     // text / table_doc / row_sample
    private String sourceFileName;
    private Integer chunkIndex;
    private float[] embedding;      // pgvector 自动映射
    private String metadata;        // JSONB
    private Integer status;         // 1=正常 0=已软删除
    private LocalDateTime createdAt;
}
```

### 3.3 新增 DTO/VO（db-genius-model）

| 类名 | 用途 |
|------|------|
| KnowledgeFileVO | 知识库文件列表项（id + originalName + fileSize + fileType + chunkCount + status + createdAt） |
| KnowledgeSearchRequest | 知识库检索请求（query + topK + threshold） |
| KnowledgeSearchResult | 检索结果（content + similarity + metadata + fileId + originalFileName） |
| KnowledgeDocListVO | 知识库文档摘要列表（按 source_file_name 聚合） |

### 3.4 新增 Mapper（db-genius-service）

#### KnowledgeFileMapper.java

标准 MyBatis-Plus BaseMapper<KnowledgeFile>，无需自定义方法。

#### KnowledgeDocumentMapper.java

```java
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /** 向量相似度检索（多租户隔离） */
    @Select("""
        SELECT id, title, content, content_type, source_file_name, chunk_index, metadata,
               1 - (embedding <=> #{queryEmbedding}::vector) AS similarity
        FROM knowledge_document
        WHERE user_id = #{userId}
          AND 1 - (embedding <=> #{queryEmbedding}::vector) >= #{threshold}
        ORDER BY embedding <=> #{queryEmbedding}::vector
        LIMIT #{topK}
    """)
    List<KnowledgeDocument> searchBySimilarity(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("userId") Long userId,
            @Param("topK") int topK,
            @Param("threshold") double threshold);
}
```

### 3.5 新增 Service 层（db-genius-service）

拆为三个 Service，职责清晰：

#### 3.5.1 KnowledgeDocumentService：文档管理

| 方法 | 说明 |
|------|------|
| uploadAndIndex(userId, file) | ① 保存文件到磁盘 + 写 knowledge_file 记录；② 异步解析文本 → 分块 → 向量化 → 写 knowledge_document；③ 完成后更新 knowledge_file.chunk_count + embedded_at |
| importDocContent(userId, dbConfigId) | 将 doc_content 按表分块 → 向量化 → 写 knowledge_document（file_id=NULL） |
| softDeleteFile(userId, fileId) | 软删除：① knowledge_file.status=0；② 批量 knowledge_document.status=0（级联软删除）；③ 不删磁盘文件（保留审计） |
| hardDeleteFile(userId, fileId) | 物理删除：① 删磁盘文件；② 删 knowledge_document 向量记录；③ 删 knowledge_file 记录（可选管理接口） |
| downloadFile(userId, fileId) | 检查归属 → 返回文件流（stored_path） |
| listFiles(userId) | 列出知识库文件列表（status=1），返回 KnowledgeFileVO 列表 |
| deleteByDbConfigId(userId, dbConfigId) | 清理某 doc_content 导入的知识库向量数据 |

#### 3.5.2 KnowledgeEmbeddingService：向量化引擎

| 方法 | 说明 |
|------|------|
| embedAndSave(text, userId, title, contentType, sourceFile, metadata) | 分块 → Embedding → 写入 knowledge_document |
| chunkText(text, chunkSize, overlap) | 滑动窗口分块 |

#### 3.5.3 KnowledgeSearchService：检索引擎

| 方法 | 说明 |
|------|------|
| search(query, userId, topK, threshold) | 用户问题向量化 → 检索 → 返回 Top-K |
| formatAsContext(results) | 将结果拼接为可注入 systemPrompt 的文本 |

### 3.6 PgVector 配置类（db-genius-web）

位置：db-genius-web/src/main/java/com/dbgenius/config/PgVectorConfig.java

```java
@Configuration
@ConditionalOnProperty(name = "db-genius.rag.enabled", havingValue = "true")
public class PgVectorConfig {

    @Value("${SPRING_AI_OPENAI_EMBEDDING_DIMENSION:1536}")
    private int embeddingDimension;

    @Bean
    @ConditionalOnMissingBean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate,
                                   EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("app")
                .vectorTableName("knowledge_document")
                .idColumnName("id")
                .contentColumnName("content")
                .embeddingColumnName("embedding")
                .metadataColumns("user_id", "title", "content_type",
                                 "source_file_name", "chunk_index", "metadata")
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .dimensions(embeddingDimension)
                .initializeSchema(false)
                .build();
    }
}
```

> @ConditionalOnProperty 保证 Embedding 未启用时不初始化 VectorStore Bean，应用正常启动。

### 3.7 新增 Agent Tool：KnowledgeSearchTool（db-genius-agent）

位置：db-genius-agent/src/main/java/com/dbgenius/agent/tool/KnowledgeSearchTool.java

**这是场景 A 的关键组件**——Agent 自主调用的检索工具。

```java
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool {

    private final KnowledgeSearchService knowledgeSearchService;

    @Tool(description = """
        Search the user's knowledge base for documents related to the query.
        Use this tool when the database schema (doc_content) does NOT contain
        enough information to answer the user's question -- for example:
        - Business meaning of a field (e.g., "what does status=3 mean?")
        - Design rationale, operational rules, or conventions
        - User mentions specific documents they uploaded earlier

        Returns the top matching document chunks with their titles.
        If no relevant documents are found, returns "No relevant documents found."
        """)
    public String searchKnowledge(
            @ToolParam(description = "The search query in natural language") String query,
            @ToolParam(description = "Current user ID for multi-tenant isolation") Long userId) {

        List<KnowledgeDocument> results = knowledgeSearchService.search(
                query, userId, 5, 0.7);

        if (results.isEmpty()) {
            return "No relevant documents found in the knowledge base.";
        }
        return knowledgeSearchService.formatAsContext(results);
    }
}
```

### 3.8 意图分类扩展：新增 KNOWLEDGE_SEARCH 意图（场景 B）

#### 3.8.1 枚举新增

IntentType.java 新增：

```java
KNOWLEDGE_SEARCH("knowledge_search", "知识库检索"),
```

#### 3.8.2 IntentClassifier systemPrompt 新增

在 IntentClassifier.buildSystemPrompt() 中的意图类型定义区域追加：

```
### knowledge_search
- 定义：用户想从自己的知识库/文档中查找信息，不涉及连接数据库
- 示例：
  - "帮我查一下上次写的数据库设计规范"
  - "我的知识库里有没有关于索引优化的文档"
  - "看看之前上传的那份分库分表方案"
  - "根据我的设计文档，这个项目应该用什么数据库"
- 边界：明确指向用户自己上传的文档资料，不涉及数据库连接
```

#### 3.8.3 新增 Handler：KnowledgeSearchHandler

位置：db-genius-agent/src/main/java/com/dbgenius/agent/intent/KnowledgeSearchHandler.java

核心逻辑：

```java
@Override
public void handle(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                   IntentClassificationResult classification, Long userId) {
    // 1. 创建/获取会话，保存用户消息
    // 2. KnowledgeSearchService.search() 检索知识库
    // 3. 检索结果注入 systemPrompt
    // 4. ChatClient 流式对话（不连数据库，类似 SimpleChatHandler）
}
```

这个 Handler **不需要连数据库**，不需要 DbSqlAgent，是独立的 AI 对话流程。

### 3.9 Agent 改造：将 KnowledgeSearchTool 注入现有 Handler

#### 3.9.1 DbSqlAgent 改造

构造时多传入 KnowledgeSearchTool，systemPrompt 追加工具使用指引：

```java
public DbSqlAgent(ChatClient chatClient, SqlExecuteTool sqlExecuteTool,
                  TerminateTool terminateTool, KnowledgeSearchTool knowledgeSearchTool,
                  String dbDocContext) {
    super("DbSqlAgent", buildSystemPrompt(dbDocContext), "...", 10,
          chatClient, sqlExecuteTool, terminateTool, knowledgeSearchTool);
}

private static String buildSystemPrompt(String dbDoc) {
    return """
            You are DB-Genius, an expert database assistant.
            ...
            ## Database Schema (STRICTLY follow this)
            The following is the authoritative database schema. All SQL must
            reference only these tables, columns, and types.

            %s

            ## Knowledge Base (supplementary)
            If the schema above lacks business context (e.g., field meanings,
            design rationale, operational rules), use the searchKnowledge tool
            to look up related documents from the user's knowledge base.
            Always cite the source document when using knowledge base info.
            """.formatted(dbDoc);
}
```

#### 3.9.2 SqlQueryHandler 改造

新增依赖注入 KnowledgeSearchTool，构造 DbSqlAgent 时多传入：

```java
// 新增字段
private final KnowledgeSearchTool knowledgeSearchTool;

// handle() 中
DbSqlAgent agent = new DbSqlAgent(chatClient, sqlExecuteTool,
        terminateTool, knowledgeSearchTool, dbDoc);
```

同理改造 CompareHandler、WorkflowHandler。

### 3.10 新增 API 接口（db-genius-web）

新增 KnowledgeController：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /knowledge/upload | 上传文档到知识库（解析 → 分块 → 向量化） |
| POST | /knowledge/import-doc/{dbConfigId} | 将 doc_content 导入知识库 |
| DELETE | /knowledge/{id} | 删除某条知识库记录 |
| DELETE | /knowledge/by-doc/{dbConfigId} | 清理某 doc_content 导入的知识库数据 |
| GET | /knowledge/list | 列出用户知识库文档摘要（按文件聚合） |

### 3.11 application.yml 配置新增

```yaml
spring:
  ai:
    openai:
      # 原有 Chat 配置不变
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
      # 新增 Embedding 配置（独立于 Chat 模型）
      embedding:
        enabled: ${SPRING_AI_OPENAI_EMBEDDING_ENABLED:false}
        api-key: ${SPRING_AI_OPENAI_EMBEDDING_API_KEY}
        base-url: ${SPRING_AI_OPENAI_EMBEDDING_BASE_URL}
        options:
          model: ${SPRING_AI_OPENAI_EMBEDDING_MODEL:text-embedding-3-small}
          dimensions: ${SPRING_AI_OPENAI_EMBEDDING_DIMENSION:1536}

db-genius:
  # 原有配置不变
  encrypt-key: ${DB_GENIUS_ENCRYPT_KEY:...}
  file-upload-dir: ${DB_GENIUS_FILE_DIR:...}
  trial: ...
  # 新增 RAG 配置
  rag:
    enabled: ${SPRING_AI_OPENAI_EMBEDDING_ENABLED:false}
    top-k: ${DB_GENIUS_RAG_TOP_K:5}
    similarity-threshold: ${DB_GENIUS_RAG_SIMILARITY_THRESHOLD:0.7}
    chunk-size: ${DB_GENIUS_RAG_CHUNK_SIZE:1000}
    chunk-overlap: ${DB_GENIUS_RAG_CHUNK_OVERLAP:200}
```

### 3.12 Schema 初始化 SQL 更新

db-genius-web/src/main/resources/db/schema.sql 追加：

```sql
-- ==================== RAG 知识库模块 ====================
-- 注意：pgvector 扩展需由 DBA 在服务器上手动安装
--   CREATE EXTENSION IF NOT EXISTS vector;

-- 知识库文件元信息表
CREATE TABLE IF NOT EXISTS knowledge_file (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    original_name VARCHAR(256) NOT NULL,
    stored_path TEXT NOT NULL,
    file_size BIGINT,
    content_type VARCHAR(128),
    file_type VARCHAR(32) NOT NULL DEFAULT 'text',
    status SMALLINT NOT NULL DEFAULT 1,
    chunk_count INT NOT NULL DEFAULT 0,
    embedded_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE knowledge_file IS '知识库文件元信息表';
COMMENT ON COLUMN knowledge_file.status IS '1=正常, 0=已软删除';
CREATE INDEX IF NOT EXISTS idx_kf_user_id ON knowledge_file(user_id);
CREATE INDEX IF NOT EXISTS idx_kf_user_status ON knowledge_file(user_id, status);

-- 知识库向量分块表
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    file_id BIGINT REFERENCES knowledge_file(id) ON DELETE SET NULL,
    title VARCHAR(512) NOT NULL,
    content TEXT NOT NULL,
    content_type VARCHAR(32) NOT NULL DEFAULT 'text',
    source_file_name VARCHAR(256),
    chunk_index INT NOT NULL DEFAULT 0,
    embedding vector(1536),
    metadata JSONB DEFAULT '{}',
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE knowledge_document IS 'RAG 知识库向量表';
COMMENT ON COLUMN knowledge_document.file_id IS '关联 knowledge_file.id，导入自 doc_content 时为 NULL';
COMMENT ON COLUMN knowledge_document.user_id IS '多租户隔离';
COMMENT ON COLUMN knowledge_document.status IS '1=正常, 0=已软删除';

CREATE INDEX IF NOT EXISTS idx_kd_user_id ON knowledge_document(user_id);
CREATE INDEX IF NOT EXISTS idx_kd_user_status ON knowledge_document(user_id, status);
CREATE INDEX IF NOT EXISTS idx_kd_file_id ON knowledge_document(file_id);
CREATE INDEX IF NOT EXISTS idx_kd_user_content_type ON knowledge_document(user_id, content_type);
```

---

## 四、doc_content 保持现有流程不动（重要）

### 当前不变逻辑

```
用户发起 SQL 查询/数据库对比/工作流对话
  -> SqlQueryHandler / CompareHandler / WorkflowHandler
    -> buildDbDocContext(userId, dbConfigIds)
      -> 直接读 db_config.doc_content
      -> 全量拼入 systemPrompt（DbSqlAgent 构建时）
    -> Agent 严格参考，生成精确 SQL
```

**不改这行代码，不在这个链路加向量检索。**

### 唯一新增的导入入口

POST /knowledge/import-doc/{dbConfigId} 是一个**手动触发**的操作：

1. 用户在前端点击「将表结构导入知识库」
2. 后端读取 db_config.doc_content
3. 按 "## Table: xxx" 分段 → Embedding → 写入 knowledge_document
4. content_type='table_doc'，方便后续区分

**不会自动导入**，不会改变现有流程。导入后 doc_content 依然在 agent 对话时全量注入 systemPrompt，向量检索只在场景 B（纯知识对话）中覆盖到它。

---

## 五、对话流程详解

### 5.1 场景 A 流程（SQL 查询 + Agent 自主检索知识库）

```
用户: "帮我查一下最近7天订单表中 status=3 的订单"
  ↓
IntentRouter → IntentClassifier → 分类为 SQL_QUERY
  ↓
SqlQueryHandler.handle()
  ├─ 从 db_config 读 doc_content → orders 表：status int, 无注释
  ├─ buildDbDocContext() → systemPrompt 包含完整表结构
  ├─ 构建 DbSqlAgent，注入 KnowledgeSearchTool
  └─ Agent 启动 ReAct 循环
       ├─ [Step 1] think(): LLM 分析发现 status 字段含义不明
       │   → 决定调用 searchKnowledge tool
       ├─ act(): searchKnowledge(query="status字段含义 orders表", userId=1)
       │   → 返回: "业务规范文档.md | status: 1=待支付 2=已支付 3=已退款..."
       ├─ [Step 2] think(): "status=3 表示已退款订单。生成查询 SQL。"
       ├─ act(): executeSql(dbConfigId=3, sql="SELECT * FROM orders...")
       └─ act(): doTerminate(summary="查询到最近7天已退款订单共 X 条")
  ↓
前端收到 SSE 流: thinking → tool_call → result → summary → done
```

### 5.2 场景 B 流程（纯知识库对话）

```
用户: "我的知识库里有没有关于分库分表的设计方案"
  ↓
IntentRouter → IntentClassifier → 分类为 KNOWLEDGE_SEARCH
  ↓
KnowledgeSearchHandler.handle()
  ├─ 创建/获取会话，保存用户消息
  ├─ search("分库分表设计方案", userId=1, topK=5, threshold=0.7)
  │   → 返回 hits: [
  │       {title: "数据库架构设计v2.md", similarity:0.92},
  │       {title: "分库分表实践.md", similarity:0.88},
  │     ]
  ├─ 构建 systemPrompt + 检索上下文
  └─ ChatClient 流式对话生成回答
       → "根据您的知识库，找到以下相关文档：
          1. 《数据库架构设计v2.md》中提到..."
  ↓
前端收到 SSE 流: content → content → ... → done
```

### 5.3 场景 C 流程（doc_content 导入知识库）

```
用户操作: 前端点击「将表结构导入知识库」
  ↓
POST /api/knowledge/import-doc/3
  ↓
KnowledgeDocumentService.importDocContent(userId=1, dbConfigId=3)
  ├─ 读取 db_config.doc_content，按 "## Table:" 分段
  ├─ 12 个表 = 12 个 chunk
  ├─ 每个 chunk → Embedding → 写入 knowledge_document（file_id=NULL）
  └─ 返回: {"imported": 12, "message": "已导入 12 个表结构到知识库"}
```

### 5.4 软删除联动流程（用户删除知识库文件）

```
用户操作: 前端知识库文件列表 -> 点击删除「数据库架构设计v2.md」
  ↓
DELETE /api/knowledge/files/5
  ↓
KnowledgeDocumentService.softDeleteFile(userId=1, fileId=5)
  ├─ ① knowledge_file: SET status=0 WHERE id=5 AND user_id=1
  ├─ ② knowledge_document: SET status=0 WHERE file_id=5
  └─ 返回: {"deletedChunks": 8, "message": "已删除文件及 8 条关联知识"}
  ↓
效果：
  - knowledge_file 列表不再展示该文件
  - 检索 SQL (WHERE kd.status=1) 自动过滤，不会返回已删除内容
  - 磁盘文件保留不删（便于审计回滚）
```

---

## 六、实施顺序（建议）

```
Phase 1 -- 环境准备（运维）
  ├─ 1.1 PostgreSQL 安装 pgvector 扩展
  ├─ 1.2 手动创建 knowledge_document 表（含 user_id）
  ├─ 1.3 更新 .env / docker-compose.yml（Embedding 环境变量）
  ├─ 1.4 申请 Embedding API Key（如果 DeepSeek 不支持）
  └─ 1.5 验证：psql 确认 vector 扩展可用

Phase 2 -- 基础能力（开发）
  ├─ 2.1 加 Maven 依赖（spring-ai-pgvector-store）
  ├─ 2.2 建 Entity + Mapper + DTO/VO（KnowledgeDocument）
  ├─ 2.3 写 PgVectorConfig 配置类
  ├─ 2.4 写 KnowledgeEmbeddingService（分块 + 向量化写入）
  ├─ 2.5 写 KnowledgeSearchService（向量检索 + 多租户过滤）
  ├─ 2.6 写 KnowledgeDocumentService（上传/导入/删除管理）
  ├─ 2.7 写 KnowledgeController（API 接口）
  └─ 2.8 单元测试：上传 Markdown → 分块 → 向量化 → 检索

Phase 3 -- Agent 集成（开发）
  ├─ 3.1 新增 IntentType.KNOWLEDGE_SEARCH
  ├─ 3.2 更新 IntentClassifier systemPrompt（新增意图描述）
  ├─ 3.3 新增 KnowledgeSearchHandler（场景 B 纯知识对话）
  ├─ 3.4 新增 KnowledgeSearchTool（场景 A Agent 自主检索 tool）
  ├─ 3.5 改造 DbSqlAgent / SqlQueryHandler：注入 KnowledgeSearchTool
  ├─ 3.6 同理改造 CompareHandler / WorkflowHandler
  └─ 3.7 注册 KnowledgeSearchHandler 到 IntentHandlerRegistry

Phase 4 -- 文档解析 & 导入（可选增强）
  ├─ 4.1 文件上传解析引擎（.txt / .md / .xlsx → 文本）
  ├─ 4.2 doc_content 导入知识库功能
  └─ 4.3 Word/PDF 解析补充

Phase 5 -- 端到端验证 & 调优
  ├─ 5.1 场景 A 测试：给无注释表结构，看 Agent 是否主动检索知识库
  ├─ 5.2 场景 B 测试：纯知识库对话
  ├─ 5.3 场景 C 测试：导入 doc_content → 检索
  ├─ 5.4 多租户验证：用户 A 搜不到用户 B 的文档
  ├─ 5.5 调优：分块大小、topK、阈值
  └─ 5.6 向量索引优化（数据量 > 10000 条加 IVFFlat/HNSW 索引）
```

---

## 七、注意事项与风险

### 7.1 Embedding 模型的选择（最关键的风险点）

| 模型 | 维度 | 提供商 | 适用性 |
|------|------|--------|--------|
| text-embedding-3-small | 1536 | OpenAI | 推荐，性价比最高 |
| text-embedding-3-large | 3072 | OpenAI | 更准但更贵，切换需重建向量 |
| text-embedding-ada-002 | 1536 | OpenAI（旧版）| 兼容性好 |
| 阿里百炼 Embedding | 1536 | 阿里云 | 国内部署选择 |
| 智谱 Embedding | 1024/2048 | 智谱 AI | 国内部署选择 |

> **关键**：DeepSeek Chat API 可能不提供 Embedding 接口。如果确认不支持，需要额外引入 Embedding 提供商。
>
> **配置隔离**：Chat 走 DeepSeek（api.deepseek.com），Embedding 走 OpenAI 或其他，互不影响。
>
> **向量维度锁定**：knowledge_document.embedding vector(1536) 写死了维度。切换模型 = 需要 DELETE + 重新向量化全部数据，迁移成本高，建议一开始就确定好 Embedding 模型。

### 7.2 Embedding 开关设计

通过 db-genius.rag.enabled=true/false 控制：
- false 时：不初始化 VectorStore Bean，KnowledgeSearchTool 返回未启用提示
- true 时：完整启动

这样可以在没有 Embedding API Key 的环境下不影响现有功能。

### 7.3 多租户隔离

- 所有 knowledge_document 查询/写入带 user_id 过滤
- 检索 SQL 中 WHERE user_id = #{userId} 不可遗漏
- Agent tool 调用时从 StpUtil.getLoginIdAsLong() 获取当前用户 ID

### 7.4 分块策略

| 文档类型 | 分块策略 |
|----------|----------|
| 通用 Markdown/TXT | 按 chunkSize=1000 字符 + overlap=200 滑动窗口 |
| doc_content 导入 | 按 "## Table: xxx" 自然分段（一个表 = 一个 chunk） |
| Excel | 按 Sheet → 行分组，转为 Markdown 表格文本 |

---

## 八、附录：关键 SQL 脚本速查

```sql
-- 1. 安装 pgvector 扩展（DBA 在 PostgreSQL 服务器上执行一次）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 验证扩展
SELECT * FROM pg_extension WHERE extname = 'vector';

-- 3. 创建知识库表
SET search_path TO app;

-- 文件元信息表
CREATE TABLE IF NOT EXISTS knowledge_file (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    original_name VARCHAR(256) NOT NULL,
    stored_path TEXT NOT NULL,
    file_size BIGINT,
    content_type VARCHAR(128),
    file_type VARCHAR(32) NOT NULL DEFAULT 'text',
    status SMALLINT NOT NULL DEFAULT 1,
    chunk_count INT NOT NULL DEFAULT 0,
    embedded_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_kf_user_id ON knowledge_file(user_id);
CREATE INDEX IF NOT EXISTS idx_kf_user_status ON knowledge_file(user_id, status);

-- 向量分块表
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    file_id BIGINT REFERENCES knowledge_file(id) ON DELETE SET NULL,
    title VARCHAR(512) NOT NULL,
    content TEXT NOT NULL,
    content_type VARCHAR(32) NOT NULL DEFAULT 'text',
    source_file_name VARCHAR(256),
    chunk_index INT NOT NULL DEFAULT 0,
    embedding vector(1536),
    metadata JSONB DEFAULT '{}',
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_kd_user_id ON knowledge_document(user_id);
CREATE INDEX IF NOT EXISTS idx_kd_user_status ON knowledge_document(user_id, status);
CREATE INDEX IF NOT EXISTS idx_kd_file_id ON knowledge_document(file_id);
CREATE INDEX IF NOT EXISTS idx_kd_user_content_type ON knowledge_document(user_id, content_type);

-- 4. 向量索引（数据量 > 10000 条后再建）
CREATE INDEX IF NOT EXISTS idx_kd_embedding
    ON knowledge_document USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 5. 查看某用户的知识库文件
SELECT id, original_name, file_type, chunk_count, status, embedded_at, created_at
FROM knowledge_file
WHERE user_id = 1 AND status = 1
ORDER BY created_at DESC;

-- 6. 软删除文件（级联标记向量数据）
UPDATE knowledge_file SET status = 0, updated_at = NOW() WHERE id = 5 AND user_id = 1;
UPDATE knowledge_document SET status = 0 WHERE file_id = 5;

-- 7. 查看某用户的知识库向量概况
SELECT user_id, content_type, COUNT(*) AS chunks, MAX(created_at) AS last_embed
FROM knowledge_document
WHERE user_id = 1 AND status = 1
GROUP BY user_id, content_type;

-- 8. 清理某 doc_content 导入的知识库数据
DELETE FROM knowledge_document WHERE user_id = 1 AND file_id IS NULL AND content_type = 'table_doc';
```
