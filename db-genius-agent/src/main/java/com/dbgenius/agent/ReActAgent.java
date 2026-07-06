package com.dbgenius.agent;

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
        return act();
    }

    protected abstract boolean think() throws Exception;

    protected abstract String act() throws Exception;
}
