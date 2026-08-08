package com.dbgenius.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String type;

    private String dbConfigIds;

    /** 会话累计消耗 token（每轮请求累加） */
    private Long totalTokens;

    /** 当前上下文占用 token（最近一次 LLM 调用的 prompt_tokens） */
    private Integer contextTokens;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
