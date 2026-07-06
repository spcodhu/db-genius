package com.dbgenius.model.vo;

import lombok.Data;

@Data
public class LoginVO {

    private String token;

    private String username;

    private String nickname;

    private String role;
}
