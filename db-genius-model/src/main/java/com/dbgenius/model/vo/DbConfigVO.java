package com.dbgenius.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DbConfigVO {

    private Long id;

    private String name;

    private String dbType;

    private String host;

    private Integer port;

    private String dbName;

    private String username;

    private Integer status;

    private String statusDesc;

    private String docContent;

    private LocalDateTime docGeneratedAt;

    private LocalDateTime createdAt;
}
