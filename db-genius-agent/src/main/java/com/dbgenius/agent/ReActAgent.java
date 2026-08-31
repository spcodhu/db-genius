package com.dbgenius.agent;

import com.dbgenius.agent.model.AgentState;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class ReActAgent extends BaseAgent {

    protected ReActAgent(String name, int maxSteps) {
        super(name, maxSteps);
    }

    @Override
    protected String step() throws Exception {
        boolean shouldAct = think();
        if (!shouldAct) {
            return "Thinking complete, no further action needed.";
        }
        // think 与 act 之间也要检查：客户端可能刚好在模型流结束后、工具执行前断开，
        // 此时绝不能再执行工具（workflow 意图下可能是写操作）
        if (state == AgentState.ABORTED || (channel != null && channel.isAborted())) {
            state = AgentState.ABORTED;
            return "Aborted by client before tool execution.";
        }
        return act();
    }

    protected abstract boolean think() throws Exception;

    protected abstract String act() throws Exception;
}
