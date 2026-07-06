package com.dbgenius.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dbgenius.model.dto.CreateUserRequest;
import com.dbgenius.model.dto.LoginRequest;
import com.dbgenius.model.entity.SysUser;
import com.dbgenius.model.vo.LoginVO;

public interface UserService extends IService<SysUser> {

    LoginVO login(LoginRequest request);

    void logout();

    void createUser(CreateUserRequest request);
}
