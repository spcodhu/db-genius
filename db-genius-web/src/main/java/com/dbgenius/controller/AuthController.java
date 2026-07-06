package com.dbgenius.controller;

import com.dbgenius.common.result.R;
import com.dbgenius.model.dto.CreateUserRequest;
import com.dbgenius.model.dto.LoginRequest;
import com.dbgenius.model.vo.LoginVO;
import com.dbgenius.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(userService.login(request));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        userService.logout();
        return R.ok();
    }

    @PostMapping("/user")
    public R<Void> createUser(@Valid @RequestBody CreateUserRequest request) {
        userService.createUser(request);
        return R.ok();
    }
}
