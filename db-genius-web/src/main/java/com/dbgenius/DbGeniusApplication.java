package com.dbgenius;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.dbgenius.mapper")
public class DbGeniusApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbGeniusApplication.class, args);
    }
}
