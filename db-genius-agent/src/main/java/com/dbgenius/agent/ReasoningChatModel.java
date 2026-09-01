package com.dbgenius.agent;

import com.dbgenius.agent.stream.StreamCancelledException;
import com.dbgenius.agent.usage.TokenUsageAccumulator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletion;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 支持 DeepSeek thinking 模式的 ChatModel 装饰器。
 *
 * <p>背景：DeepSeek V4 默认开启 thinking 模式，发生 tool call 的轮次要求后续请求
 * 必须回传 {@code reasoning_content}，否则 API 返回 400。Spring AI 1.1.x 的
 * {@link OpenAiChatModel} 只在响应侧解析 {@code reasoningContent}（放入
 * {@link AssistantMessage} metadata），构造请求时并不回传。本类在 {@code call()}
 * 路径上补齐回传逻辑，其余行为与官方实现对齐；{@code stream()} 直接委托给官方模型
 * （流式 simple chat 不涉及 tool call，无需回传）。
 *
 * <p>{@link #streamAggregated} 供工具调用 Agent 使用：流式拉取（reasoning 增量实时
 * 回调，避免 think 阶段长时间无事件），内部聚合成与 {@code call()} 同构的响应，
 * 保留 tool call 轮次的 reasoning_content 回传能力。
 *
 * <p><b>观测埋点（断点 3 的手写补偿）：</b>{@code call()} 与 {@code streamAggregated()}
 * 为保住 reasoning_content 回传而直连 {@link OpenAiApi}，绕过了 {@link OpenAiChatModel}
 * 自带的 gen_ai.* 埋点（只有 {@code stream()} 转发给 delegate 才享受官方观测）。
 * 因此本类在两个直连方法上按 OTel GenAI 语义手写 {@code gen_ai.client.operation}
 * Observation（span 名 {@code chat <model>}），并用 {@link MeterRegistry} 记录 TTFT
 * （首条 reasoning/content 增量到达时刻）。未装配时 registry 为 NOOP、零开销。
 *
 * <p>仅支持文本消息（本项目无多模态场景）；不含重试。
 * 未来升级到 Spring AI 2.0（原生支持回传）后本类可整体删除。
 */
@Slf4j
public class ReasoningChatModel implements ChatModel {

    /** 与 {@link OpenAiChatModel} 一致的 metadata key，下游统一按此读取推理内容。 */
    public static final String REASONING_CONTENT_KEY = "reasoningContent";

    /**
     * 客户端断开导致流被主动取消时写入的 finishReason（OpenAI 协议无此枚举值，属本项目内部约定）。
     * 调用方据此判定"本次是半截结果"，落库为中断消息而非正常回答。
     */
    public static final String FINISH_REASON_CLIENT_ABORTED = "CLIENT_ABORTED";

    /** 备用 metadata key：防御 Spring AI 升级或供应商映射差异（OpenAI 兼容协议下各厂商字段名不一）。 */
    private static final String[] REASONING_FALLBACK_KEYS = {"reasoning_content", "thinking", "reasoning"};

    /**
     * 归一化模型推理内容，兼容不同供应商/Spring AI 版本的字段差异。
     *
     * <p>OpenAI 兼容协议下推理模型（DeepSeek、OpenAI o 系、Qwen3、Kimi、GLM 等）
     * 统一返回 {@code reasoning_content}，Spring AI 解析后放入 AssistantMessage metadata
     * 的 {@link #REASONING_CONTENT_KEY}；非推理模型（Ollama、gpt-4o 等）则缺失或为空串
     * （{@code call()} 路径写入 ""）。本方法依次探测主 key 与备用 key，trim 后为空即返回
     * {@code null}，落库统一为 NULL，不存空串。
     *
     * @param metadata AssistantMessage 的 metadata
     * @return 归一化后的推理内容；缺失或空白返回 null
     */
    public static String normalizeReasoningContent(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(REASONING_CONTENT_KEY);
        if (value == null) {
            for (String fallbackKey : REASONING_FALLBACK_KEYS) {
                value = metadata.get(fallbackKey);
                if (value != null) {
                    break;
                }
            }
        }
        if (value == null) {
            return null;
        }
        String reasoning = value.toString().strip();
        return reasoning.isEmpty() ? null : reasoning;
    }

    /**
     * 判定一次 {@link #streamAggregated} 结果是否为「客户端断开导致的半截结果」。
     *
     * @return true 表示内容不完整，调用方应落库为中断消息而非正常回答
     */
    public static boolean isClientAborted(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getMetadata() == null) {
            return false;
        }
        return FINISH_REASON_CLIENT_ABORTED.equals(response.getResult().getMetadata().getFinishReason());
    }

    private final OpenAiApi openAiApi;
    private final OpenAiChatModel delegate;
    private final OpenAiChatOptions defaultOptions;
    private final ToolCallingManager toolCallingManager;

    /** 本轮请求的 token 用量累加器，由 ChatModelFactory 注入，可为 null（不记账） */
    private TokenUsageAccumulator tokenUsageAccumulator;

    /** 观测注册表：默认 NOOP（测试/未装配时零开销），生产由 ChatModelFactory 注入容器 bean */
    private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

    /** TTFT 直方图所需的指标注册表，由 ChatModelFactory 注入；null 表示不记录 TTFT */
    private MeterRegistry meterRegistry;

    /**
     * {@code gen_ai.system} 属性值（供应商标识，如 deepseek/openai），由 ChatModelFactory
     * 按用户配置注入。取枚举化的 provider code，符合 metric tag 基数纪律。
     */
    private String genAiSystem = "unknown";

    /**
     * 是否只回传最后一条 assistant 消息的 reasoning_content（Tier-2 单轮内瘦身，默认关闭）。
     * 由 ChatModelFactory 按配置注入；各供应商对缺失历史 reasoning 的容忍度不一，需灰度验证。
     */
    private boolean dropStaleReasoning;

    /** 取消信号（客户端断开时置位），由 Agent 注入；null 表示不可取消。 */
    private BooleanSupplier cancelSignal;

    public ReasoningChatModel(OpenAiApi openAiApi, OpenAiChatModel delegate,
                              OpenAiChatProperties chatProperties, ToolCallingManager toolCallingManager) {
        this.openAiApi = openAiApi;
        this.delegate = delegate;
        this.defaultOptions = chatProperties.getOptions();
        this.toolCallingManager = toolCallingManager;
    }

    public void setTokenUsageAccumulator(TokenUsageAccumulator tokenUsageAccumulator) {
        this.tokenUsageAccumulator = tokenUsageAccumulator;
    }

    public void setObservationRegistry(ObservationRegistry observationRegistry) {
        if (observationRegistry != null) {
            this.observationRegistry = observationRegistry;
        }
    }

    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void setGenAiSystem(String genAiSystem) {
        if (genAiSystem != null && !genAiSystem.isBlank()) {
            this.genAiSystem = genAiSystem;
        }
    }

    public void setDropStaleReasoning(boolean dropStaleReasoning) {
        this.dropStaleReasoning = dropStaleReasoning;
    }

    /**
     * 设置取消信号（客户端断开时置位）。每个流式 chunk 检查一次；一旦为 true，
     * {@link #streamAggregated} 立即向上游传播 cancel 释放供应商连接，并把已收到的
     * 增量聚合成 partial 响应正常返回（finishReason = {@link #FINISH_REASON_CLIENT_ABORTED}），
     * <b>不抛异常</b>，调用方据此落库半截内容。
     *
     * <p>放在实例字段而非方法参数上是安全的：{@code ChatModelFactory.createSession}
     * 每次请求都新建一个 ReasoningChatModel，实例天然按请求隔离。
     */
    public void setCancelSignal(BooleanSupplier cancelSignal) {
        this.cancelSignal = cancelSignal;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Observation observation = startChatObservation();
        try (Observation.Scope scope = observation.openScope()) {
            OpenAiChatOptions requestOptions = mergeOptions(prompt.getOptions());
            ChatCompletionRequest request = createRequest(prompt.getInstructions(), requestOptions, false);
            ChatCompletion chatCompletion = this.openAiApi.chatCompletionEntity(request).getBody();
            Assert.notNull(chatCompletion, "chat completion must not be null");
            ChatResponse response = toChatResponse(chatCompletion);
            recordUsage(response.getMetadata().getUsage());
            enrichChatObservation(observation, response);
            return response;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return this.delegate.stream(prompt);
    }

    /**
     * 流式执行一次补全，reasoning 增量实时回调，流结束后聚合成与 {@link #call(Prompt)}
     * 完全同构的 {@link ChatResponse} 返回。
     *
     * <p>存在的意义：Agent 的 think() 若用阻塞 {@code call()}，DeepSeek thinking 模式下
     * 单步要静默数十秒才一次性拿到结果，SSE 前端表现为"步骤成批到达、不实时"。改为
     * 流式后推理内容随生成随推送；聚合结果（完整 reasoning_content + 合并分片后的
     * tool calls）继续驱动原有的 tool call 判定与 reasoning_content 回传流程，下游零改动。
     *
     * <p>必须走 {@link #createRequest} 自建请求（而非 {@code delegate.stream()}），
     * 否则历史消息中的 reasoning_content 回传会丢失，tool call 轮次 DeepSeek 返回 400。
     *
     * @param reasoningDeltaConsumer 每收到一段 reasoning 增量回调一次，可为 null
     */
    public ChatResponse streamAggregated(Prompt prompt, Consumer<String> reasoningDeltaConsumer) {
        return streamAggregated(prompt, reasoningDeltaConsumer, null);
    }

    /**
     * 同 {@link #streamAggregated(Prompt, Consumer)}，另将 content 增量实时回调
     * （供总结等场景做前端打字机渲染），可为 null。
     *
     * <p>若通过 {@link #setCancelSignal} 注入了取消信号且信号置位，本方法立即向上游传播
     * cancel（释放供应商 HTTP 连接），并把<b>已收到的增量</b>聚合成 partial 响应正常返回，
     * finishReason 为 {@link #FINISH_REASON_CLIENT_ABORTED}（可用 {@link #isClientAborted} 判定）。
     *
     * @param contentDeltaConsumer 每收到一段 content 增量回调一次，可为 null
     */
    public ChatResponse streamAggregated(Prompt prompt, Consumer<String> reasoningDeltaConsumer,
                                         Consumer<String> contentDeltaConsumer) {
        Observation observation = startChatObservation();
        long startNanos = System.nanoTime();
        AtomicBoolean ttftRecorded = new AtomicBoolean(false);
        Consumer<String> reasoningConsumer = wrapForTtft(reasoningDeltaConsumer, ttftRecorded, startNanos);
        Consumer<String> contentConsumer = wrapForTtft(contentDeltaConsumer, ttftRecorded, startNanos);
        try (Observation.Scope scope = observation.openScope()) {
            ChatResponse response = doStreamAggregated(prompt, reasoningConsumer, contentConsumer);
            // ⚠️ 客户端中断不是错误：CLIENT_ABORTED 只进 finish_reasons 属性，绝不 observation.error()，
            // 与「客户端断开不打 ERROR」约定一致，避免用户正常点停止污染错误率指标
            enrichChatObservation(observation, response);
            return response;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * 开启一次 {@code gen_ai.client.operation} Observation（OTel GenAI 语义，span 名 {@code chat <model>}）。
     * 与 Spring AI 官方实现对齐的低基数属性；token 用量与 finish_reasons 在响应后由
     * {@link #enrichChatObservation} 补成高基数属性。
     */
    private Observation startChatObservation() {
        return Observation.createNotStarted("gen_ai.client.operation", observationRegistry)
                .contextualName("chat " + modelName())
                .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                .lowCardinalityKeyValue("gen_ai.system", genAiSystem)
                .lowCardinalityKeyValue("gen_ai.request.model", modelName())
                .start();
    }

    /** 响应落账：finish_reasons 与 token 用量写为 span 属性（高基数，不进 metric tag）。 */
    private void enrichChatObservation(Observation observation, ChatResponse response) {
        if (response == null) {
            return;
        }
        if (response.getResult() != null && response.getResult().getMetadata() != null
                && StringUtils.hasText(response.getResult().getMetadata().getFinishReason())) {
            observation.highCardinalityKeyValue("gen_ai.response.finish_reasons",
                    "[" + response.getResult().getMetadata().getFinishReason() + "]");
        }
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage usage = response.getMetadata().getUsage();
            observation.highCardinalityKeyValue("gen_ai.usage.input_tokens",
                    String.valueOf(usage.getPromptTokens()));
            observation.highCardinalityKeyValue("gen_ai.usage.output_tokens",
                    String.valueOf(usage.getCompletionTokens()));
        }
    }

    /**
     * 给增量回调包一层 TTFT 计时：第一条 reasoning <b>或</b> content 增量到达即记录
     * （非推理模型没有 reasoning 增量，只看 reasoning 会漏记）。未装配 MeterRegistry 时原样返回。
     */
    private Consumer<String> wrapForTtft(Consumer<String> consumer, AtomicBoolean ttftRecorded, long startNanos) {
        if (meterRegistry == null) {
            return consumer;
        }
        return delta -> {
            if (ttftRecorded.compareAndSet(false, true)) {
                ttftTimer().record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            }
            if (consumer != null) {
                consumer.accept(delta);
            }
        };
    }

    /** TTFT 直方图：SSE 场景下用户主观「快不快」的决定性指标。tag 只用枚举值（供应商 + 模型名）。 */
    private Timer ttftTimer() {
        return Timer.builder("dbgenius.llm.ttft")
                .description("Time to first delta of streaming LLM calls")
                .tags("gen_ai.system", genAiSystem, "gen_ai.request.model", modelName())
                .register(meterRegistry);
    }

    private String modelName() {
        return defaultOptions.getModel() != null ? defaultOptions.getModel() : "unknown";
    }

    /** {@link #streamAggregated} 的执行体：流式拉取 + 聚合，观测与 TTFT 由外层壳负责。 */
    private ChatResponse doStreamAggregated(Prompt prompt, Consumer<String> reasoningDeltaConsumer,
                                            Consumer<String> contentDeltaConsumer) {
        OpenAiChatOptions requestOptions = mergeOptions(prompt.getOptions());
        ChatCompletionRequest request = createRequest(prompt.getInstructions(), requestOptions, true);

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCallsByIndex = new LinkedHashMap<>();
        StringBuilder finishReason = new StringBuilder();
        StringBuilder responseId = new StringBuilder();
        StringBuilder responseModel = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<OpenAiApi.Usage> usageRef = new java.util.concurrent.atomic.AtomicReference<>();

        boolean cancelled = false;
        Consumer<ChatCompletionChunk> chunkHandler = chunk -> {
            if (cancelSignal != null && cancelSignal.getAsBoolean()) {
                // 抛出后 Reactor 向上游传播 cancel，供应商连接立即释放（不再空转烧 token）
                throw new StreamCancelledException();
            }
            if (chunk.id() != null) {
                responseId.setLength(0);
                responseId.append(chunk.id());
            }
            if (chunk.model() != null) {
                responseModel.setLength(0);
                responseModel.append(chunk.model());
            }
            if (chunk.usage() != null) {
                // stream_options.include_usage 开启后，用量可能在独立帧或任意帧携带
                usageRef.set(chunk.usage());
            }
            List<ChatCompletionChunk.ChunkChoice> choices = chunk.choices();
            if (CollectionUtils.isEmpty(choices)) {
                return; // 无内容帧（如 usage-only chunk）
            }
            ChatCompletionChunk.ChunkChoice choice = choices.get(0);
            ChatCompletionMessage delta = choice.delta();
            if (delta != null) {
                // 只跳过 null/空串，不能用 hasText：LLM 常把 "\n" 作为独立增量帧发出，
                // hasText 会把纯空白帧整体丢弃，导致前端与聚合体系统性丢失换行
                if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                    reasoningBuilder.append(delta.reasoningContent());
                    if (reasoningDeltaConsumer != null) {
                        reasoningDeltaConsumer.accept(delta.reasoningContent());
                    }
                }
                if (delta.content() != null && !delta.content().isEmpty()) {
                    contentBuilder.append(delta.content());
                    if (contentDeltaConsumer != null) {
                        contentDeltaConsumer.accept(delta.content());
                    }
                }
                if (!CollectionUtils.isEmpty(delta.toolCalls())) {
                    mergeToolCallChunks(toolCallsByIndex, delta.toolCalls());
                }
            }
            if (choice.finishReason() != null) {
                finishReason.setLength(0);
                finishReason.append(choice.finishReason().name());
            }
        };

        try {
            this.openAiApi.chatCompletionStream(request).doOnNext(chunkHandler).blockLast();
        } catch (StreamCancelledException e) {
            cancelled = true;
        } catch (RuntimeException e) {
            // Reactor 会把 doOnNext 抛出的异常包装成 ReactiveException，需解包再判定
            if (Exceptions.unwrap(e) instanceof StreamCancelledException) {
                cancelled = true;
            } else {
                throw e;
            }
        }

        if (cancelled) {
            log.info("[ReasoningChatModel] stream cancelled by client, partial content={} chars, reasoning={} chars",
                    contentBuilder.length(), reasoningBuilder.length());
            finishReason.setLength(0);
            finishReason.append(FINISH_REASON_CLIENT_ABORTED);
        }

        ChatResponse response = toAggregatedChatResponse(responseId.toString(), responseModel.toString(),
                finishReason.toString(), contentBuilder.toString(), reasoningBuilder.toString(),
                toolCallsByIndex, usageRef.get());
        recordUsage(response.getMetadata().getUsage());
        return response;
    }

    /**
     * 合并流式 tool_call 分片：首片携带 id/type/name，后续片只带 arguments 增量，
     * 按 OpenAI 分片协议以 index 归并（arguments 为字符串拼接）。
     */
    private void mergeToolCallChunks(Map<Integer, ToolCallAccumulator> accumulator,
                                     List<ChatCompletionMessage.ToolCall> fragments) {
        for (ChatCompletionMessage.ToolCall fragment : fragments) {
            int index = fragment.index() != null ? fragment.index() : 0;
            ToolCallAccumulator acc = accumulator.computeIfAbsent(index, i -> new ToolCallAccumulator());
            if (fragment.id() != null) {
                acc.id = fragment.id();
            }
            if (fragment.type() != null) {
                acc.type = fragment.type();
            }
            if (fragment.function() != null) {
                if (fragment.function().name() != null) {
                    acc.name = fragment.function().name();
                }
                if (fragment.function().arguments() != null) {
                    acc.arguments.append(fragment.function().arguments());
                }
            }
        }
    }

    /** 流式 tool_call 分片的累加器，字段随 chunk 逐步补齐。 */
    private static final class ToolCallAccumulator {
        private String id = "";
        private String type = "function";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }

    /** 与 {@link #toChatResponse} 输出结构对齐，仅数据来源从整块响应换成流式聚合结果。 */
    private ChatResponse toAggregatedChatResponse(String id, String model, String finishReason,
                                                  String content, String reasoning,
                                                  Map<Integer, ToolCallAccumulator> toolCallsByIndex,
                                                  OpenAiApi.Usage usage) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("id", id);
        metadata.put("role", ChatCompletionMessage.Role.ASSISTANT.name());
        metadata.put("index", 0);
        metadata.put("finishReason", finishReason);
        metadata.put("refusal", "");
        metadata.put("annotations", List.of());
        metadata.put(REASONING_CONTENT_KEY, reasoning);

        List<AssistantMessage.ToolCall> toolCalls = toolCallsByIndex.values().stream()
                .map(acc -> new AssistantMessage.ToolCall(acc.id, acc.type, acc.name, acc.arguments.toString()))
                .toList();

        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(content)
                .properties(metadata)
                .toolCalls(toolCalls)
                .build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder()
                .id(id)
                .model(model);
        if (usage != null) {
            metadataBuilder.usage(toSpringUsage(usage));
        }
        return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)), metadataBuilder.build());
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return OpenAiChatOptions.fromOptions(this.defaultOptions);
    }

    /**
     * 以 yml 默认配置为底，叠加运行时 options（agent 传入的是 ToolCallingChatOptions，
     * 只携带工具相关字段；若是 OpenAiChatOptions 则按官方方式整体合并）。
     */
    private OpenAiChatOptions mergeOptions(ChatOptions runtimeOptions) {
        OpenAiChatOptions options = OpenAiChatOptions.fromOptions(this.defaultOptions);
        if (runtimeOptions instanceof OpenAiChatOptions openAiOptions) {
            options = ModelOptionsUtils.merge(openAiOptions, options, OpenAiChatOptions.class);
        }
        else if (runtimeOptions instanceof ToolCallingChatOptions toolOptions) {
            options.setToolCallbacks(toolOptions.getToolCallbacks());
            options.setToolNames(toolOptions.getToolNames());
            options.setToolContext(toolOptions.getToolContext());
            options.setInternalToolExecutionEnabled(toolOptions.getInternalToolExecutionEnabled());
            if (toolOptions.getModel() != null) {
                options.setModel(toolOptions.getModel());
            }
        }
        return options;
    }

    private ChatCompletionRequest createRequest(List<Message> instructions, OpenAiChatOptions requestOptions,
                                                boolean stream) {
        int lastAssistantIndex = lastAssistantIndex(instructions);
        List<ChatCompletionMessage> messages = new ArrayList<>();
        for (int i = 0; i < instructions.size(); i++) {
            boolean keepReasoning = lastAssistantIndex < 0 || i == lastAssistantIndex;
            messages.addAll(toApiMessages(instructions.get(i), keepReasoning));
        }

        ChatCompletionRequest request = new ChatCompletionRequest(messages, stream);
        request = ModelOptionsUtils.merge(requestOptions, request, ChatCompletionRequest.class);

        // 与官方 OpenAiChatModel 对齐：stream_options 仅允许随 stream=true 发送，
        // 否则 DeepSeek 等供应商直接 400（stream_options should be set along with stream = true）
        if (!stream && request.streamOptions() != null) {
            request = request.streamOptions(null);
        }

        List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(requestOptions);
        if (!CollectionUtils.isEmpty(toolDefinitions)) {
            request = ModelOptionsUtils.merge(OpenAiChatOptions.builder()
                    .tools(toFunctionTools(toolDefinitions))
                    .extraBody(request.extraBody())
                    .build(), request, ChatCompletionRequest.class);
        }
        return request;
    }

    /**
     * 与 {@link OpenAiChatModel} 的消息转换一致，唯一差异：ASSISTANT 消息把 metadata 中的
     * reasoningContent 回传为 {@code reasoning_content} 字段（DeepSeek thinking + tool call 的硬性要求）。
     *
     * <p>开启 {@code dropStaleReasoning} 时，只对<b>最后一条</b> assistant 消息回传 reasoning_content，
     * 更早的一律置空。DeepSeek 的硬性要求针对的是紧邻的 tool-call 轮次，而历史 reasoning 在单轮多步
     * ReAct 中会线性累积成上下文的大头。各供应商对此行为不一致（可能返回 400），因此默认关闭、灰度打开。</p>
     */
    private List<ChatCompletionMessage> toApiMessages(Message message, boolean keepReasoning) {
        return switch (message.getMessageType()) {
            case USER, SYSTEM -> List.of(new ChatCompletionMessage(message.getText(),
                    ChatCompletionMessage.Role.valueOf(message.getMessageType().name())));
            case ASSISTANT -> {
                var assistantMessage = (AssistantMessage) message;
                List<ChatCompletionMessage.ToolCall> toolCalls = null;
                if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
                    toolCalls = assistantMessage.getToolCalls().stream()
                            .map(tc -> new ChatCompletionMessage.ToolCall(tc.id(), tc.type(),
                                    new ChatCompletionFunction(tc.name(), tc.arguments())))
                            .toList();
                }
                Object reasoning = keepReasoning
                        ? assistantMessage.getMetadata().get(REASONING_CONTENT_KEY) : null;
                yield List.of(new ChatCompletionMessage(assistantMessage.getText(),
                        ChatCompletionMessage.Role.ASSISTANT, null, null, toolCalls, null, null, null,
                        reasoning instanceof String s ? s : null));
            }
            case TOOL -> {
                var toolMessage = (ToolResponseMessage) message;
                toolMessage.getResponses()
                        .forEach(response -> Assert.isTrue(response.id() != null,
                                "ToolResponseMessage must have an id"));
                yield toolMessage.getResponses().stream()
                        .map(tr -> new ChatCompletionMessage(tr.responseData(),
                                ChatCompletionMessage.Role.TOOL, tr.name(), tr.id(), null, null, null, null, null))
                        .toList();
            }
        };
    }

    /**
     * 计算「最后一条 assistant 消息」的下标：开启陈旧 reasoning 丢弃时，只有它保留
     * reasoning_content 回传。未开启时返回 -1（调用方视作全部保留）。
     */
    private int lastAssistantIndex(List<Message> messages) {
        if (!dropStaleReasoning) {
            return -1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage) {
                return i;
            }
        }
        return -1;
    }

    private List<FunctionTool> toFunctionTools(List<ToolDefinition> toolDefinitions) {
        return toolDefinitions.stream()
                .map(td -> new FunctionTool(
                        new FunctionTool.Function(td.description(), td.name(), td.inputSchema())))
                .toList();
    }

    private ChatResponse toChatResponse(ChatCompletion chatCompletion) {
        List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("id", chatCompletion.id() != null ? chatCompletion.id() : "");
            metadata.put("role", choice.message().role() != null ? choice.message().role().name() : "");
            metadata.put("index", choice.index() != null ? choice.index() : 0);
            metadata.put("finishReason", choice.finishReason() != null ? choice.finishReason().name() : "");
            metadata.put("refusal",
                    StringUtils.hasText(choice.message().refusal()) ? choice.message().refusal() : "");
            metadata.put("annotations",
                    choice.message().annotations() != null ? choice.message().annotations() : List.of());
            metadata.put(REASONING_CONTENT_KEY,
                    choice.message().reasoningContent() != null ? choice.message().reasoningContent() : "");

            List<AssistantMessage.ToolCall> toolCalls = choice.message().toolCalls() == null ? List.of()
                    : choice.message().toolCalls().stream()
                            .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function",
                                    tc.function().name(), tc.function().arguments()))
                            .toList();

            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content(choice.message().content())
                    .properties(metadata)
                    .toolCalls(toolCalls)
                    .build();
            ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                    .finishReason((String) metadata.get("finishReason"))
                    .build();
            return new Generation(assistantMessage, generationMetadata);
        }).toList();

        ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder()
                .id(chatCompletion.id() != null ? chatCompletion.id() : "")
                .model(chatCompletion.model() != null ? chatCompletion.model() : "");
        if (chatCompletion.usage() != null) {
            metadataBuilder.usage(toSpringUsage(chatCompletion.usage()));
        }
        return new ChatResponse(generations, metadataBuilder.build());
    }

    /** OpenAI 协议用量 → Spring AI {@link Usage}，字段缺失按 0 处理。 */
    private static Usage toSpringUsage(OpenAiApi.Usage usage) {
        Integer prompt = usage.promptTokens() != null ? usage.promptTokens().intValue() : 0;
        Integer completion = usage.completionTokens() != null ? usage.completionTokens().intValue() : 0;
        Integer total = usage.totalTokens() != null ? usage.totalTokens().intValue() : prompt + completion;
        return new DefaultUsage(prompt, completion, total);
    }

    private void recordUsage(Usage usage) {
        if (tokenUsageAccumulator != null && usage != null && usage.getTotalTokens() > 0) {
            tokenUsageAccumulator.add(usage);
        }
    }
}
