package com.cloudstorage;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。见 AGENTS.md 3.1：只有 web 模块放启动类与 Controller。
 */
@EnableAsync
@EnableScheduling
@MapperScan("com.cloudstorage.domain.mapper")
@SpringBootApplication(scanBasePackages = "com.cloudstorage")
public class CloudStorageApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudStorageApplication.class, args);
    }
}
