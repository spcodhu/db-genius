package com.dbgenius;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.dbgenius.mapper")
@EnableAsync
public class DbGeniusApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbGeniusApplication.class, args);
    }
}
