package com.dbgenius.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationVO {

    private Long id;
    private String title;
    private String type;
    private String dbConfigIds;
    private Long totalTokens;
    private Integer contextTokens;
    private LocalDateTime createdAt;
}
