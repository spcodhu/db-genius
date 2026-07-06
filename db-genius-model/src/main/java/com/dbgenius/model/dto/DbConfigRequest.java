package com.dbgenius.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DbConfigRequest {

    @NotBlank(message = "Config name is required")
    private String name;

    private String dbType;

    @NotBlank(message = "Host is required")
    private String host;

    @NotNull(message = "Port is required")
    private Integer port;

    @NotBlank(message = "Database name is required")
    private String dbName;

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;
}
