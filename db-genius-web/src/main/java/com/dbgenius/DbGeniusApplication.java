package com.dbgenius;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;

/**
 * 排除 {@link MongoAutoConfiguration}：classpath 上的 {@code mongodb-driver-sync} 只服务于
 * 用户目标库（{@code MongoDbAdapter} 每次自建 {@code MongoClients.create(uri)}），
 * 但该自动配置只要看到驱动类就会装配一个指向默认 localhost:27017 的 MongoClient bean，
 * 其后台 monitor 线程会在启动时及之后每 10s 刷一次 Connection refused。
 */
@SpringBootApplication(exclude = MongoAutoConfiguration.class)
@MapperScan("com.dbgenius.mapper")
public class DbGeniusApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbGeniusApplication.class, args);
    }
}
