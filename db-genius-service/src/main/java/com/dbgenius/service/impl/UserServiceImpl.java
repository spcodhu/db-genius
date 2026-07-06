package com.dbgenius.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.mapper.SysUserMapper;
import com.dbgenius.model.dto.CreateUserRequest;
import com.dbgenius.model.dto.LoginRequest;
import com.dbgenius.model.entity.SysUser;
import com.dbgenius.model.vo.LoginVO;
import com.dbgenius.service.UserService;
import cn.hutool.crypto.digest.BCrypt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements UserService {

    private static final String ADMIN_ROLE = "admin";
    private static final String USER_ROLE = "user";

    @Override
    public LoginVO login(LoginRequest request) {
        SysUser user = getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername()));
        if (user == null) {
            throw new BusinessException(401, "Invalid username or password");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException(403, "Account is disabled");
        }
        if (!matchPassword(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(401, "Invalid username or password");
        }

        StpUtil.login(user.getId());
        StpUtil.getSession().set("role", user.getRole());

        LoginVO vo = new LoginVO();
        vo.setToken(StpUtil.getTokenValue());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());
        return vo;
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public void createUser(CreateUserRequest request) {
        String role = (String) StpUtil.getSession().get("role");
        if (!ADMIN_ROLE.equals(role)) {
            throw new BusinessException(403, "Only administrators can create users");
        }

        long count = count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername()));
        if (count > 0) {
            throw new BusinessException(400, "Username already exists");
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(hashPassword(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setRole(request.getRole() != null ? request.getRole() : USER_ROLE);
        user.setStatus(1);
        save(user);
    }

    private String hashPassword(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }

    private boolean matchPassword(String rawPassword, String hashedPassword) {
        return BCrypt.checkpw(rawPassword, hashedPassword);
    }
}
