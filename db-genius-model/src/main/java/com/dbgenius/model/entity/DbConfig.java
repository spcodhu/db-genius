package com.dbgenius.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.dbgenius.model.enums.DbConfigStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("db_config")
public class DbConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String dbType;

    private String host;

    private Integer port;

    private String dbName;

    private String username;

    private String passwordEncrypted;

    private DbConfigStatus status;

    private String docContent;

    private LocalDateTime docGeneratedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
