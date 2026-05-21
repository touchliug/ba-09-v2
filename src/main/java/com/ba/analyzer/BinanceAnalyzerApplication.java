package com.ba.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 币安USDT永续合约数据分析系统启动类
 * 启用Spring Boot自动配置和定时任务调度
 */
@SpringBootApplication
@EnableScheduling
public class BinanceAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BinanceAnalyzerApplication.class, args);
    }
}
