package com.dbgenius.mq;

/**
 * MQ topology constants for db-config verify & doc generation.
 */
public final class DbConfigMqConstants {

    private DbConfigMqConstants() {
    }

    public static final String EXCHANGE = "dbgenius.dbconfig.exchange";
    public static final String QUEUE_VERIFY = "dbgenius.dbconfig.verify";
    public static final String ROUTING_KEY_VERIFY = "dbconfig.verify";

    public static final String DLX = "dbgenius.dbconfig.dlx";
    public static final String QUEUE_VERIFY_DLQ = "dbgenius.dbconfig.verify.dlq";
    public static final String ROUTING_KEY_VERIFY_DLQ = "dbconfig.verify.dlq";

    /** 动作：验证连接并生成文档（配置创建/更新后的默认动作） */
    public static final String ACTION_VERIFY_AND_DOC = "VERIFY_AND_DOC";

    /** 动作：手动刷新文档（数据库结构变更后，重新验证连接并重新生成文档） */
    public static final String ACTION_REFRESH_DOC = "REFRESH_DOC";
}
