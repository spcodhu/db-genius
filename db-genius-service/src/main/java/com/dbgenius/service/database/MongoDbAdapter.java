package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.common.exception.ErrorCode;
import com.dbgenius.common.util.SqlSafetyGuard;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import com.dbgenius.model.metadata.ColumnMetadata;
import com.dbgenius.model.metadata.IndexMetadata;
import com.dbgenius.model.metadata.SchemaMetadata;
import com.dbgenius.model.metadata.TableMetadata;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonType;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MongoDB 适配器（策略模式 Strategy 之具体策略 + 适配器模式 Adapter，非 JDBC）。
 *
 * <p><b>适配思路：</b>MongoDB 无表/列概念，这里把「集合（Collection）即表」，
 * 列信息通过对集合前 50 条文档采样、合并所有文档的 key 来推断，
 * 类型取 BSON 类型名（如 string/objectId/array）。由于文档型库无固定 Schema，
 * 采样结果仅供 LLM 参考，不保证覆盖所有字段。</p>
 *
 * <p>MongoDB 可以为无认证部署，因此 username/password 均允许为空。</p>
 */
@Slf4j
@Component
public class MongoDbAdapter implements DatabaseAdapter {

    /** Schema 抽取时的文档采样上限（字段推断依据） */
    private static final int SAMPLE_SIZE = 50;

    /** find/aggregate 返回文档数的硬性上限，防止大结果集撑爆上下文 */
    private static final int MAX_LIMIT = 100;

    @Override
    public DbType getType() {
        return DbType.MONGODB;
    }

    /**
     * 拼接 MongoDB 连接串：mongodb://[user:password@]host:port/
     * 账密非空才拼认证段；密码做 URL 编码，避免含 @:/? 等特殊字符时截断连接串。
     */
    private String buildConnectionUri(DbConfig config, String decryptedPassword) {
        StringBuilder uri = new StringBuilder("mongodb://");
        if (hasText(config.getUsername()) && hasText(decryptedPassword)) {
            uri.append(URLEncoder.encode(config.getUsername(), StandardCharsets.UTF_8))
                    .append(':')
                    .append(URLEncoder.encode(decryptedPassword, StandardCharsets.UTF_8))
                    .append('@');
        }
        uri.append(config.getHost()).append(':').append(config.getPort()).append('/');
        return uri.toString();
    }

    @Override
    public boolean testConnection(DbConfig config, String decryptedPassword) {
        try (MongoClient client = MongoClients.create(buildConnectionUri(config, decryptedPassword))) {
            // ping 是最轻量的服务端探活命令
            client.getDatabase(config.getDbName()).runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            log.warn("测试 MongoDB 连接失败（{}:{} / {}）：{}",
                    config.getHost(), config.getPort(), config.getDbName(), e.getMessage());
            return false;
        }
    }

    /**
     * 抽取 Schema：集合即表；rowCount 用 estimatedDocumentCount（近似、快）；
     * 列由采样文档的 key 合并推断；索引取 listIndexes 的 name 与 key 字段列表。
     * 异常不抛出，写入 {@link SchemaMetadata#errorMessage}。
     */
    @Override
    public SchemaMetadata extractSchema(DbConfig config, String decryptedPassword) {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType(getType().getCode());
        schema.setDbTypeDisplayName(getType().getDisplayName());
        schema.setDatabaseName(config.getDbName());
        schema.setHost(config.getHost());
        schema.setPort(config.getPort());

        try (MongoClient client = MongoClients.create(buildConnectionUri(config, decryptedPassword))) {
            MongoDatabase database = client.getDatabase(config.getDbName());
            for (String collectionName : database.listCollectionNames()) {
                TableMetadata table = new TableMetadata();
                table.setName(collectionName);
                MongoCollection<Document> collection = database.getCollection(collectionName);
                table.setRowCount(collection.estimatedDocumentCount());
                table.setColumns(sampleColumns(collection));
                table.setIndexes(extractIndexes(collection));
                schema.getTables().add(table);
            }
        } catch (Exception e) {
            // 与 JDBC 系一致：部分失败不中断，错误写入 errorMessage
            log.error("读取 MongoDB 元数据失败（{}）：{}", config.getDbName(), e.getMessage(), e);
            schema.setErrorMessage(e.getMessage());
        }
        return schema;
    }

    /**
     * 采样前 {@link #SAMPLE_SIZE} 条文档，合并所有 key 推断列。
     * 字段一律视为可空（文档型库无约束）；字段名为 "_id" 时视为主键。
     */
    private List<ColumnMetadata> sampleColumns(MongoCollection<Document> collection) {
        // LinkedHashMap 保持字段首次出现的顺序，输出更稳定
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Document doc : collection.find().limit(SAMPLE_SIZE)) {
            // 转成 BsonDocument 才能拿到真实的 BSON 类型（而非 Java 包装类型）
            doc.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry())
                    .forEach((key, value) -> fieldTypes.putIfAbsent(key, bsonTypeName(value.getBsonType())));
        }
        List<ColumnMetadata> columns = new ArrayList<>();
        fieldTypes.forEach((name, type) -> {
            ColumnMetadata column = new ColumnMetadata();
            column.setName(name);
            column.setType(type);
            column.setNullable(true);
            column.setPrimaryKey("_id".equals(name));
            column.setComment(null);
            columns.add(column);
        });
        return columns;
    }

    /** 抽取集合索引：name 与 key 的字段列表。 */
    private List<IndexMetadata> extractIndexes(MongoCollection<Document> collection) {
        List<IndexMetadata> indexes = new ArrayList<>();
        for (Document index : collection.listIndexes()) {
            IndexMetadata meta = new IndexMetadata();
            meta.setName(index.getString("name"));
            Document key = index.get("key", Document.class);
            meta.setColumns(key != null ? new ArrayList<>(key.keySet()) : List.of());
            indexes.add(meta);
        }
        return indexes;
    }

    /**
     * 执行 MongoDB 查询命令（供 agent 模块 SqlExecuteTool 调用）。
     *
     * <p>命令为 JSON：{@code {"collection":"c","operation":"find|count|distinct|aggregate",
     * "filter":{...},"field":"x","pipeline":[...],"limit":100}}。
     * 执行前经过 {@link SqlSafetyGuard#assertMongoCommandSafe} 硬性拦截 drop 类操作；
     * find 默认限 100 条（上限 100）；aggregate 管道含 $out/$merge 时拒绝（写操作）。</p>
     *
     * @return 结果 JSON 字符串（find/aggregate 为文档数组，count 为数字，distinct 为 {"values": [...]}）
     */
    public String executeCommand(DbConfig config, String decryptedPassword, String commandJson) {
        // 安全红线先行：drop/dropDatabase 直接 403，不进入任何执行逻辑
        SqlSafetyGuard.assertMongoCommandSafe(commandJson);

        Document command = parseCommand(commandJson);
        String collectionName = command.getString("collection");
        if (!hasText(collectionName)) {
            throw new BusinessException(400, "MongoDB command requires 'collection'");
        }
        String operation = command.getString("operation");
        if (!hasText(operation)) {
            throw new BusinessException(400, "MongoDB command requires 'operation'");
        }
        Document filter = command.get("filter", Document.class);
        if (filter == null) {
            filter = new Document();
        }

        try (MongoClient client = MongoClients.create(buildConnectionUri(config, decryptedPassword))) {
            MongoCollection<Document> collection =
                    client.getDatabase(config.getDbName()).getCollection(collectionName);
            return switch (operation.toLowerCase()) {
                case "find" -> {
                    // limit 缺省 100，且硬性钳制在 [1, 100]
                    int limit = clamp(command.getInteger("limit", MAX_LIMIT));
                    List<Document> results = new ArrayList<>();
                    collection.find(filter).limit(limit).into(results);
                    yield results.stream().map(Document::toJson).collect(Collectors.joining(",", "[", "]"));
                }
                case "count" -> String.valueOf(collection.countDocuments(filter));
                case "distinct" -> {
                    String field = command.getString("field");
                    if (!hasText(field)) {
                        throw new BusinessException(400, "MongoDB distinct command requires 'field'");
                    }
                    List<Object> values = new ArrayList<>();
                    collection.distinct(field, filter, Object.class).into(values);
                    // 借 Document 的编解码能力把任意 BSON 值序列化为 JSON
                    yield new Document("values", values).toJson();
                }
                case "aggregate" -> {
                    List<Document> pipeline = command.getList("pipeline", Document.class, List.of());
                    assertPipelineReadOnly(pipeline);
                    List<Document> results = new ArrayList<>();
                    collection.aggregate(pipeline).into(results);
                    yield results.stream().map(Document::toJson).collect(Collectors.joining(",", "[", "]"));
                }
                default -> throw new BusinessException(400, "Unsupported MongoDB operation: " + operation);
            };
        }
    }

    /**
     * 只读判断：find/count/distinct 恒为只读；aggregate 仅当管道不含 $out/$merge 时只读；
     * JSON 解析失败按非只读处理（宁严勿宽）。
     */
    @Override
    public boolean isReadOnlyStatement(String commandJson) {
        if (commandJson == null || commandJson.isBlank()) {
            return false;
        }
        try {
            Document command = Document.parse(commandJson);
            String operation = command.getString("operation");
            if (operation == null) {
                return false;
            }
            return switch (operation.toLowerCase()) {
                case "find", "count", "distinct" -> true;
                case "aggregate" -> {
                    List<Document> pipeline = command.getList("pipeline", Document.class, List.of());
                    yield pipeline.stream().noneMatch(stage ->
                            stage.containsKey("$out") || stage.containsKey("$merge"));
                }
                default -> false;
            };
        } catch (Exception e) {
            // 非法 JSON 无法判定，一律按非只读处理
            return false;
        }
    }

    /** MongoDB 支持无认证部署，username/password 允许为空，仅校验连接三要素。 */
    @Override
    public void validateRequest(DbConfigRequest request) {
        if (!hasText(request.getHost())) {
            throw new BusinessException(ErrorCode.DB_FIELD_REQUIRED, "host", getType().getDisplayName());
        }
        if (request.getPort() == null) {
            throw new BusinessException(ErrorCode.DB_FIELD_REQUIRED, "port", getType().getDisplayName());
        }
        if (!hasText(request.getDbName())) {
            throw new BusinessException(ErrorCode.DB_FIELD_REQUIRED, "dbName", getType().getDisplayName());
        }
    }

    /** 解析命令 JSON，解析失败统一转成 400 业务异常。 */
    private Document parseCommand(String commandJson) {
        try {
            return Document.parse(commandJson);
        } catch (Exception e) {
            throw new BusinessException(400, "Invalid MongoDB command JSON: " + e.getMessage());
        }
    }

    /** aggregate 管道含 $out/$merge 属于写操作，硬性拒绝。 */
    private void assertPipelineReadOnly(List<Document> pipeline) {
        for (Document stage : pipeline) {
            if (stage.containsKey("$out") || stage.containsKey("$merge")) {
                throw new BusinessException(403,
                        "安全红线：aggregate 管道中的 $out / $merge 写操作被系统禁止执行。");
            }
        }
    }

    /** 把 limit 钳制在 [1, MAX_LIMIT]。 */
    private int clamp(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    /** BSON 类型名转小写展示名（objectId 保留驼峰，与 Mongo 习惯一致）。 */
    private String bsonTypeName(BsonType type) {
        return switch (type) {
            case STRING -> "string";
            case OBJECT_ID -> "objectId";
            case ARRAY -> "array";
            case DOCUMENT -> "object";
            case DOUBLE -> "double";
            case INT32 -> "int";
            case INT64 -> "long";
            case BOOLEAN -> "bool";
            case DATE_TIME -> "date";
            case NULL -> "null";
            case BINARY -> "binData";
            case DECIMAL128 -> "decimal";
            case REGULAR_EXPRESSION -> "regex";
            case TIMESTAMP -> "timestamp";
            default -> type.name().toLowerCase();
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
