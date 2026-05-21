# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

币安USDT永续合约数据分析系统 (Binance USDT-M Futures Analyzer)。从币安API获取合约K线和持仓量数据，通过多种分析策略筛选符合条件的交易对，定时调度执行并输出报告文件，同时提供REST API。

## Tech Stack

- Java 21, Spring Boot 3.2.5, Maven
- OkHttp 4.12 (带HTTP代理访问币安API)
- Caffeine (本地缓存)
- Lombok, Jackson
- JUnit 5 + Mockito (测试)

## Build & Run Commands

```bash
# 编译
mvn compile

# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=ShortTermRiseAnalyzerTest

# 打包
mvn package -DskipTests

# 启动应用 (需要代理访问币安API)
mvn spring-boot:run

# 使用test profile启动 (禁用代理)
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

## Architecture

### Core Flow

1. `SymbolService` 加载/更新USDT永续合约列表 (从`data/symbols.json`或币安API)
2. `DataFetchService` 批量并发获取K线和持仓量数据，通过 `DataCacheService` (Caffeine) 缓存
3. 各 `Analyzer` 实现对数据执行分析逻辑，返回 `AnalysisReport`
4. `AnalysisScheduler` 定时调度分析器，`ReportWriter` 输出报告到 `reports/` 目录
5. `AnalysisController` 暴露REST API供手动触发和查询结果

### Analyzer Pattern

所有分析器实现 `Analyzer` 接口。日K线类分析器继承 `AbstractKlineAnalyzer`，模板方法模式：
- `getDefaultParams()` — 从配置读取默认参数
- `resolveParams(requestParams)` — 合并请求参数与默认参数
- `doAnalyze(klineMap, params)` — 核心分析逻辑
- `buildDescription(params)` — 生成报告描述

`Analyzer` 接口支持两种调用：`analyze(symbols)` 用默认配置，`analyze(symbols, params)` 用请求参数覆盖。

### Scheduling

- 每天6:00 — 更新合约列表
- 每天8:05 — 执行日常分析 (除ShortTermRiseAnalyzer外的所有已启用分析器)
- 每5分钟 — 执行短期急涨分析

启动时 `@PostConstruct` 预加载K线数据到缓存。

### Configuration

所有分析器参数在 `application.yml` 的 `binance.analysis.*` 下配置，每个分析器有 `enabled` 开关。代理、并发、缓存TTL等也在此配置。

## Key Conventions

- 分析器名称使用中文 (如 "短期急涨"、"底部吸筹")，API路径中直接使用中文名
- 报告文件按日期分目录: `reports/yyyyMMdd/yyyyMMdd_HHmmss_类型.txt`
- 新增分析器需要：实现类 + 单元测试 + application.yml配置项 + AnalysisController中的参数说明
- 测试使用Mockito mock `DataFetchService`，不依赖外部API
