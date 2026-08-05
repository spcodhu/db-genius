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

    /** 模型思考内容（reasoning_content），供应商不返回或为空时为 NULL */
    private String reasoningContent;

    /** 工具调用记录 JSON 文本（[{id,type,name,arguments}]），无工具调用时为 NULL */
    private String toolCalls;

    private String fileUrl;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
