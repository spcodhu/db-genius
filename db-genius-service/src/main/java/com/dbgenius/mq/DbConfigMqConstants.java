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
}
