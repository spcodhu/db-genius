package com.dbgenius.config;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbgenius.model.entity.SysUser;
import com.dbgenius.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserService userService;

    @Override
    public void run(String... args) {
        long count = userService.count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin"));
        if (count == 0) {
            SysUser admin = new SysUser();
            admin.setUsername("admin");
            admin.setPasswordHash(BCrypt.hashpw("admin123", BCrypt.gensalt()));
            admin.setNickname("Administrator");
            admin.setRole("admin");
            admin.setStatus(1);
            userService.save(admin);
            log.info("Default admin user created (admin / admin123)");
        }
    }
}
