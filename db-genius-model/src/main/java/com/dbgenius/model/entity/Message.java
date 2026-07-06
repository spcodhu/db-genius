package com.dbgenius.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private String role;

    private String content;

    private Integer step;

    private String type;

    private String fileUrl;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
