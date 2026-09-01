package com.dbgenius.agent.metrics;

import com.dbgenius.model.vo.TokenUsageVO;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * db-genius 业务指标埋点（OTel gen_ai.* 只覆盖通用 LLM 语义，反映本系统好坏的信号需自打）。
 *
 * <p><b>tag 基数纪律：</b>{@code userId}、{@code conversationId}、{@code taskId}、SQL 原文
 * <b>绝不能</b>作为 metric tag（时间序列爆炸会把 Prometheus 打垮）——它们属于 trace 属性。
 * 这里的 tag 只用意图、工具名、数据库类型、终止原因这类<b>枚举值</b>。</p>
 *
 * <p>装配方式与 Agent 其他可选组件一致：Handler 把本 bean 经 {@code setAgentMetrics}
 * 注入每次运行新建的 Agent 实例；未装配（测试等场景）时各调用点跳过，行为与现状一致。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentMetrics {

    private final MeterRegistry registry;

    /**
     * Agent 运行终态：收敛方式计数 + 步数分布。
     *
     * @param reason terminate_tool（doTerminate 正常结束）/ max_steps（步数耗尽，最强的
     *               任务失败先行指标）/ hard_stop（LoopBreaker 硬熔断）/ aborted（客户端中断）
     *               / error（异常）
     */
    public void recordTermination(String agent, String reason, int steps) {
        registry.counter("dbgenius.agent.termination", "agent", agent, "reason", reason).increment();
        registry.summary("dbgenius.agent.steps", "agent", agent).record(steps);
    }

    /** 重复调用拦截：模型陷入死循环的直接信号，按工具名分维度。 */
    public void recordLoopBlocked(String tool) {
        registry.counter("dbgenius.loopbreaker.blocked", "tool", tool).increment();
    }

    /**
     * 单轮内上下文压缩触发：{@code tier} = elide（Tier-1 零成本遮蔽）/ summarize
     * （Tier-3 LLM 摘要，秒级调用，触发率高说明上下文策略需要调）。
     */
    public void recordCompact(String tier, int savedTokens) {
        registry.counter("dbgenius.context.compact", "tier", tier).increment();
        registry.summary("dbgenius.context.saved_tokens", "tier", tier).record(savedTokens);
    }

    /**
     * 破坏性 SQL 拦截数（安全红线）。⚠️ 不是越低越好——它衡量「护栏在工作」，
     * 需要告警的是趋势突增（prompt 改坏 / 模型不听话）。
     */
    public void recordSqlBlocked(String dbType) {
        registry.counter("dbgenius.sql.blocked", "db_type", dbType).increment();
    }

    /** 意图分类置信度分布 + 澄清触发计数（clarified = 需澄清或置信度低于阈值）。 */
    public void recordIntent(String intentCode, double confidence, boolean clarified) {
        registry.summary("dbgenius.intent.confidence", "intent", intentCode).record(confidence);
        if (clarified) {
            registry.counter("dbgenius.intent.clarify", "intent", intentCode).increment();
        }
    }

    /**
     * 单轮 token 用量与上下文占用率分布（数据源是已全量记账的 TokenUsageAccumulator，
     * 此处只是顺手落直方图，不新增计算；乘单价即成本，单价在变，不埋进指标）。
     */
    public void recordUsage(String agent, TokenUsageVO usage) {
        registry.summary("dbgenius.agent.tokens", "agent", agent).record(usage.getTotalTokens());
        if (usage.getContextWindow() != null && usage.getContextWindow() > 0) {
            registry.summary("dbgenius.agent.context.occupancy", "agent", agent)
                    .record((double) usage.getContextTokens() / usage.getContextWindow());
        }
    }
}
