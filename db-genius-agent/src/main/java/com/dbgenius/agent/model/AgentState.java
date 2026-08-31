package com.dbgenius.agent.model;

public enum AgentState {
    IDLE,
    RUNNING,
    FINISHED,
    /** 客户端主动断开（终止会话）导致提前收敛：非错误，半截内容需落库并标记 */
    ABORTED,
    ERROR
}
