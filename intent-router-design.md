# 意图识别模块设计方案

## 1. 设计目标

- 前端对接**单一统一接口** `POST /chat`，替代现有 `/chat/sql`、`/chat/workflow`、`/chat/compare` 三个接口
- 意图识别模块自动路由到：SQL 对话查询、工作流、数据库对比、简单会话问答
- 分类过程通过 SSE **实时推送状态**给前端
- 低置信度时**反问用户确认意图**
- 分类时传入**最近 N 条历史对话**作为上下文
- 架构优雅，新增意图类型零配置自动注册

## 2. 架构总览

```
[Frontend]
    │
    ▼
POST /chat  (统一入口, SSE)
    │
    ▼
┌──────────────────────────────────────┐
│           IntentRouter               │  ← 编排器
│                                      │
│  1. SSE 推送 "classifying" 事件       │
│  2. 加载最近 N 条历史消息作为上下文     │
│  3. 调用 IntentClassifier            │
│  4. 判断 confidence                  │
│     ├─ >= 阈值 → 直接路由            │
│     └─ < 阈值  → SSE 推送 "clarify"  │
│                  事件，等用户确认       │
│  5. 从 Registry 获取 Handler         │
│  6. Handler 执行，返回 SSE 流         │
└──────────────────────────────────────┘
               │
    ┌──────────┼──────────┬────────────────┐
    ▼          ▼          ▼                ▼
SimpleChatHandler  SqlQueryHandler  WorkflowHandler  CompareHandler
 (直接流式回复)    (→ DbSqlAgent)  (→ DbWorkflowAgent) (→ DbCompareAgent)
```

## 3. 核心设计模式

| 模式 | 职责 | 选择理由 |
|------|------|----------|
| **LLM Router** | 用 LLM + Structured Output 做意图分类 | 无需训练专用模型，天然处理模糊语义，扩展只需改 prompt |
| **Strategy** | 每种意图对应一个 Handler 实现 | 解耦分类与执行，单一职责 |
| **Registry（自动发现）** | Handler 通过 `@Component` + 接口自注册到 Map | 新增意图只需加一个类，零配置 |
| **Structured Output** | LLM 返回强类型 JSON（Spring AI BeanOutputConverter） | 避免正则解析，类型安全 |
| **Fallback + Clarify** | 低置信度反问用户 | 保证路由准确性 |

## 4. 模块归属

```
db-genius-model/    → IntentType 枚举、IntentClassificationResult、UnifiedChatRequest DTO、新 SSE 事件类型
db-genius-agent/    → IntentClassifier（LLM 分类器）、IntentHandler 接口、4 个 Handler 实现
db-genius-web/      → 新 ChatController（统一接口）、IntentRouter、IntentHandlerRegistry
db-genius-service/  → ConversationService 增加查询最近 N 条消息方法
```

## 5. 详细组件设计

### 5.1 意图枚举

```java
// db-genius-model/.../enums/IntentType.java
public enum IntentType {
    SIMPLE_CHAT("simple_chat", "简单会话问答"),
    SQL_QUERY("sql_query", "SQL 对话查询"),
    WORKFLOW("workflow", "复杂工作流"),
    DB_COMPARE("db_compare", "数据库对比");

    private final String code;
    private final String description;
}
```

### 5.2 分类结果

```java
// db-genius-model/.../vo/IntentClassificationResult.java
public record IntentClassificationResult(
    IntentType intent,
    double confidence,
    String reasoning,
    boolean needsClarification
) {}
```

### 5.3 统一请求 DTO

```java
// db-genius-model/.../dto/UnifiedChatRequest.java
public record UnifiedChatRequest(
    @NotBlank String message,
    Long conversationId,
    List<Long> dbConfigIds,
    Long preDbConfigId,
    Long testDbConfigId,
    List<Long> fileIds,
    IntentType confirmedIntent  // 用户确认意图时传入，跳过分类
) {}
```

当 `confirmedIntent` 不为 null 时，跳过 LLM 分类，直接路由。这是低置信度反问后用户确认意图的回传通道。

### 5.4 SSE 事件扩展

在现有 `SseEvent.type` 基础上新增：

| type | 含义 | content 示例 |
|------|------|-------------|
| `classifying` | 正在识别意图 | `"正在分析您的问题..."` |
| `classified` | 意图识别完成 | `{"intent": "SQL_QUERY", "confidence": 0.95}` |
| `clarify` | 需要用户确认意图 | `{"question": "...", "options": [...]}` |
| `routing` | 正在路由到对应处理器 | `"正在启动 SQL 查询智能体..."` |

已有类型（`thinking`、`step`、`sql`、`result`、`summary`、`done`、`error`、`file_parsed`）保持不变。

### 5.5 意图分类器

```java
// db-genius-agent/.../intent/IntentClassifier.java
@Component
public class IntentClassifier {

    private final ChatClient chatClient;
    private static final double CONFIDENCE_THRESHOLD = 0.7;

    public IntentClassificationResult classify(String userMessage, List<Message> recentHistory, ChatContext context) {
        String systemPrompt = buildClassificationPrompt(context);
        String historyText = formatHistory(recentHistory);

        return chatClient.prompt()
            .system(systemPrompt)
            .user(historyText + "\n\n当前用户消息: " + userMessage)
            .call()
            .entity(IntentClassificationResult.class);
    }

    private String buildClassificationPrompt(ChatContext context) {
        // 见 5.5.1
    }
}
```

#### 5.5.1 分类 System Prompt 设计

```text
你是一个意图分类器。根据用户的消息和对话历史，判断用户意图并返回 JSON。

## 意图类型定义

### SIMPLE_CHAT
- 定义：不涉及数据库操作的简单对话（问候、闲聊、概念解释、通用知识问答）
- 示例："你好"、"什么是索引？"、"谢谢"
- 边界：即使提到数据库概念，只要不需要实际执行查询就属于此类

### SQL_QUERY
- 定义：需要连接数据库执行 SQL 查询、数据分析、表结构查看
- 示例："查一下用户表有多少条数据"、"帮我看看最近7天的订单"、"user 表的结构是什么"
- 前置条件：用户需已选择数据库连接（dbConfigIds 非空）
- 边界：单次或少量 SQL 操作，不涉及文件处理

### WORKFLOW
- 定义：复杂的多步骤数据库操作，通常涉及文件上传、批量处理、数据导入导出
- 示例："把这个 Excel 的数据导入到用户表"、"根据这份文件批量更新价格"
- 前置条件：通常伴随文件上传（fileIds 非空），或明确描述多步骤流程
- 边界：涉及文件处理或明确的多步骤操作流程

### DB_COMPARE
- 定义：对比两个数据库的结构差异，生成部署 SQL
- 示例："对比预发和测试环境的数据库"、"帮我看看这两个库的差异"、"生成部署SQL"
- 前置条件：需要两个数据库连接（preDbConfigId 和 testDbConfigId 非空）
- 边界：涉及跨库结构对比

## 上下文信息
- 用户是否已选择数据库: {hasDbConfig}
- 用户是否上传了文件: {hasFiles}
- 用户是否提供了对比数据库: {hasCompareConfig}

## 输出格式
返回 JSON：
{
  "intent": "枚举值之一",
  "confidence": 0.0-1.0,
  "reasoning": "判断依据（简洁）",
  "needsClarification": false
}

规则：
1. 如果上下文缺少前置条件（如需要数据库但用户未选择），设 needsClarification=true
2. 如果真正无法确定意图，设 confidence < 0.7 且 needsClarification=true
3. 对话历史中如果连续是同一类型的操作，当前模糊消息应倾向于延续该意图
```

### 5.6 IntentHandler 接口

```java
// db-genius-agent/.../intent/IntentHandler.java
public interface IntentHandler {

    IntentType supportedIntent();

    SseEmitter handle(UnifiedChatRequest request, IntentClassificationResult classification, Long userId);
}
```

### 5.7 Handler Registry

```java
// db-genius-web/.../intent/IntentHandlerRegistry.java
@Component
public class IntentHandlerRegistry {

    private final Map<IntentType, IntentHandler> handlers;

    public IntentHandlerRegistry(List<IntentHandler> handlerList) {
        this.handlers = handlerList.stream()
            .collect(Collectors.toMap(IntentHandler::supportedIntent, Function.identity()));
    }

    public IntentHandler getHandler(IntentType type) {
        IntentHandler handler = handlers.get(type);
        if (handler == null) {
            return handlers.get(IntentType.SIMPLE_CHAT);
        }
        return handler;
    }

    public List<IntentType> supportedIntents() {
        return List.copyOf(handlers.keySet());
    }
}
```

### 5.8 IntentRouter（核心编排器）

```java
// db-genius-web/.../intent/IntentRouter.java
@Component
public class IntentRouter {

    private final IntentClassifier classifier;
    private final IntentHandlerRegistry registry;
    private final ConversationService conversationService;
    private static final int HISTORY_CONTEXT_SIZE = 5;

    public SseEmitter route(UnifiedChatRequest request, Long userId) {
        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 如果用户已确认意图，跳过分类
                if (request.confirmedIntent() != null) {
                    dispatchToHandler(emitter, request, request.confirmedIntent(), userId);
                    return;
                }

                // 2. 推送 classifying 状态
                sendEvent(emitter, SseEvent.of(taskId, 0, "classifying", "正在分析您的问题..."));

                // 3. 加载历史上下文
                List<Message> history = loadRecentHistory(request.conversationId());

                // 4. 构建分类上下文
                ChatContext context = buildChatContext(request);

                // 5. LLM 分类
                IntentClassificationResult result = classifier.classify(
                    request.message(), history, context);

                // 6. 推送分类结果
                sendEvent(emitter, SseEvent.of(taskId, 0, "classified", result));

                // 7. 判断是否需要确认
                if (result.needsClarification() || result.confidence() < 0.7) {
                    sendClarificationEvent(emitter, result, request);
                    return;
                }

                // 8. 路由到 Handler
                sendEvent(emitter, SseEvent.of(taskId, 0, "routing",
                    "正在启动" + result.intent().getDescription() + "..."));
                dispatchToHandler(emitter, request, result.intent(), userId);

            } catch (Exception e) {
                sendEvent(emitter, SseEvent.error(taskId, e.getMessage()));
                emitter.complete();
            }
        });

        return emitter;
    }

    private void sendClarificationEvent(SseEmitter emitter, IntentClassificationResult result,
                                         UnifiedChatRequest request) {
        Map<String, Object> clarifyContent = Map.of(
            "question", "我不太确定您的意图，请选择：",
            "options", buildOptions(result, request),
            "reasoning", result.reasoning()
        );
        sendEvent(emitter, SseEvent.of(taskId, 0, "clarify", clarifyContent));
        emitter.complete();
    }
}
```

### 5.9 四个 Handler 实现

#### SimpleChatHandler

```java
@Component
public class SimpleChatHandler implements IntentHandler {

    private final ChatClient chatClient;
    private final ConversationService conversationService;

    @Override
    public IntentType supportedIntent() { return IntentType.SIMPLE_CHAT; }

    @Override
    public SseEmitter handle(UnifiedChatRequest request, IntentClassificationResult classification, Long userId) {
        SseEmitter emitter = new SseEmitter(60_000L);

        CompletableFuture.runAsync(() -> {
            // 直接流式调用 LLM，逐 token 通过 SSE 推送
            // type 使用 "content" 标识流式文本片段
            // 最后发送 "done"
            Conversation conv = getOrCreateConversation(userId, request, "simple_chat");
            conversationService.saveMessage(conv.getId(), "user", request.message(), null, "user");

            Flux<String> stream = chatClient.prompt()
                .user(request.message())
                .stream()
                .content();

            StringBuilder fullContent = new StringBuilder();
            stream.doOnNext(token -> {
                fullContent.append(token);
                sendEvent(emitter, SseEvent.of(taskId, 0, "content", token));
            }).doOnComplete(() -> {
                conversationService.saveMessage(conv.getId(), "assistant", fullContent.toString(), -1, "summary");
                sendEvent(emitter, SseEvent.done(taskId));
                emitter.complete();
            }).subscribe();
        });

        return emitter;
    }
}
```

#### SqlQueryHandler

```java
@Component
public class SqlQueryHandler implements IntentHandler {

    @Override
    public IntentType supportedIntent() { return IntentType.SQL_QUERY; }

    @Override
    public SseEmitter handle(UnifiedChatRequest request, IntentClassificationResult classification, Long userId) {
        // 复用现有 ChatController 中 /chat/sql 的逻辑：
        // 1. 校验 dbConfigIds
        // 2. 构建 dbDoc context
        // 3. new DbSqlAgent(...)
        // 4. agent.runStream(message)
        // 逻辑直接从现有 ChatController.chatSql() 提取
    }
}
```

#### WorkflowHandler / CompareHandler

同理，分别封装现有 `/chat/workflow` 和 `/chat/compare` 的逻辑。

### 5.10 统一 Controller

```java
// db-genius-web/.../controller/ChatController.java（重写）
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final IntentRouter intentRouter;
    private final ConversationService conversationService;

    /**
     * 统一对话入口
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody @Valid UnifiedChatRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return intentRouter.route(request, userId);
    }

    @GetMapping("/conversations")
    public R<List<ConversationVO>> listConversations() { ... }

    @GetMapping("/conversations/{id}/messages")
    public R<List<Message>> getMessages(@PathVariable Long id) { ... }

    @DeleteMapping("/conversations/{id}")
    public R<Void> deleteConversation(@PathVariable Long id) { ... }
}
```

## 6. SSE 事件流完整时序

### 场景 A：简单问答

```
→ POST /chat { message: "你好" }
← SSE: { type: "classifying", content: "正在分析您的问题..." }
← SSE: { type: "classified", content: { intent: "SIMPLE_CHAT", confidence: 0.98 } }
← SSE: { type: "content", content: "你好" }
← SSE: { type: "content", content: "！我是" }
← SSE: { type: "content", content: "DB-Genius..." }
← SSE: { type: "done" }
```

### 场景 B：SQL 查询

```
→ POST /chat { message: "查一下用户表数据量", dbConfigIds: [1] }
← SSE: { type: "classifying", content: "正在分析您的问题..." }
← SSE: { type: "classified", content: { intent: "SQL_QUERY", confidence: 0.95 } }
← SSE: { type: "routing", content: "正在启动 SQL 查询智能体..." }
← SSE: { type: "thinking", content: "已加载数据库结构..." }
← SSE: { type: "step", step: 1, content: "分析意图..." }
← SSE: { type: "step", step: 2, content: "SELECT COUNT(*)..." }
← SSE: { type: "summary", content: "..." }
← SSE: { type: "done" }
```

### 场景 C：低置信度反问

```
→ POST /chat { message: "帮我看看这个表" }
← SSE: { type: "classifying", content: "正在分析您的问题..." }
← SSE: { type: "classified", content: { intent: "SQL_QUERY", confidence: 0.55 } }
← SSE: { type: "clarify", content: {
    "question": "我不太确定您的意图，请选择：",
    "options": [
      { "intent": "SQL_QUERY", "label": "查询数据库表结构" },
      { "intent": "SIMPLE_CHAT", "label": "只是想了解相关概念" }
    ]
  }}
← SSE: [connection ends]

// 用户选择后前端发起新请求
→ POST /chat { message: "帮我看看这个表", confirmedIntent: "SQL_QUERY", dbConfigIds: [1] }
← SSE: { type: "routing", content: "正在启动 SQL 查询智能体..." }
← SSE: ... (正常 Agent 流程)
```

## 7. 数据库变更

无需修改数据库 schema。`Conversation.type` 字段已是 varchar，直接存储 IntentType 对应的 code 值。

## 8. 实施步骤

### Phase 1：基础设施（db-genius-model + db-genius-service）

1. 新增 `IntentType` 枚举
2. 新增 `IntentClassificationResult` record
3. 新增 `UnifiedChatRequest` DTO
4. `ConversationService` 新增 `getRecentMessages(Long conversationId, int limit)` 方法

### Phase 2：分类器（db-genius-agent）

5. 新增 `IntentClassifier` 组件
6. 编写并调试分类 System Prompt
7. 新增 `IntentHandler` 接口

### Phase 3：Handler 实现（db-genius-agent）

8. 实现 `SimpleChatHandler`
9. 实现 `SqlQueryHandler`（从现有 ChatController 提取逻辑）
10. 实现 `WorkflowHandler`（从现有 ChatController 提取逻辑）
11. 实现 `CompareHandler`（从现有 ChatController 提取逻辑）

### Phase 4：编排与接入（db-genius-web）

12. 实现 `IntentHandlerRegistry`
13. 实现 `IntentRouter`
14. 重写 `ChatController`：统一入口 + 删除旧的 `/chat/sql`、`/chat/workflow`、`/chat/compare`

### Phase 5：文档与清理

15. 更新 `api-docs.yaml`：移除旧接口，新增 `POST /chat` 统一接口文档
16. 更新 `README.md` 相关说明

## 9. 扩展指南

| 未来场景 | 操作 |
|---------|------|
| 新增意图（如 "数据可视化"） | 1. `IntentType` 加枚举值 2. 写一个 `Handler` 类加 `@Component` 3. 更新分类 Prompt |
| 调整分类精度 | 修改 `IntentClassifier` 的 System Prompt |
| 接入不同模型做分类 | 为分类器注入不同的 `ChatClient` bean（可用更轻量模型降低延迟） |
| 加入 embedding 预筛选 | 在 `IntentClassifier.classify()` 前加向量相似度预过滤 |
| 多轮意图跟踪 | 在 `IntentRouter` 中缓存当前会话意图，短时间内复用 |

## 10. 关键设计决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 分类延迟处理 | 接受 ~1s 延迟，SSE 实时推送状态 | 用户可感知系统在工作 |
| 低置信度策略 | 反问用户确认 | 比默认降级更精准 |
| 历史上下文 | 传入最近 5 条消息 | 平衡上下文质量与 token 消耗 |
| 旧接口 | 直接废弃 | 简化维护，前端统一对接 |
| Handler 注册方式 | Spring IoC 自动发现 | 零配置，新增即可用 |
| 分类模型 | 复用现有 DeepSeek | 避免额外依赖，后续可独立替换 |
