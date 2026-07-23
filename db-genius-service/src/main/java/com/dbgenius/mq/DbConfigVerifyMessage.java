package com.dbgenius.mq;

import java.io.Serializable;

/**
 * Message triggering connection verify + doc generation for a db config.
 */
public record DbConfigVerifyMessage(Long configId) implements Serializable {
}
