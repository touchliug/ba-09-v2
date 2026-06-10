package com.ba.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * 币安USDT永续合约数据分析系统启动类
 * 启用Spring Boot自动配置和定时任务调度
 */
@SpringBootApplication
@EnableScheduling
public class BinanceAnalyzerApplication {

    public static void main(String[] args) {
        // 统一以 UTC 运行: 与币安日K收盘点(UTC 00:00)对齐, 不随部署服务器(瑞典VPS)的本地时区漂移。
        // 必须在 SpringApplication.run 之前设置, 否则调度器/日志已读取旧默认时区。
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(BinanceAnalyzerApplication.class, args);
    }
}
