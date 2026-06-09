# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

币安USDT永续合约数据分析系统 (Binance USDT-M Futures Analyzer)。从币安API获取合约K线、持仓量(OI)和资金费率数据，持久化到MySQL，通过多种分析策略筛选符合条件的交易对，定时调度执行并输出报告文件，同时提供REST API。

## Tech Stack

- Java 21, Spring Boot 3.2.5, Maven
- OkHttp 4.12 (带HTTP代理访问币安API)
- MySQL + Spring JDBC (JdbcTemplate + HikariCP) — 数据持久化与缓存层
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

# 启动应用 (需要代理访问币安API + 可访问的MySQL)
mvn spring-boot:run

# 使用test profile启动 (禁用代理、缩短分析窗口)
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

运行前需要一个名为 `ba` 的MySQL数据库 (见 `application.yml` 的 `spring.datasource.url`)。密码通过环境变量 `MYSQL_PASSWORD` 提供。表结构由 `JdbcDataStore.init()` 在启动时自动创建 (`klines`、`open_interest`、`funding_rate`)。

## Architecture

### Core Flow

1. `SymbolService` 加载/更新USDT永续合约列表 (从 `data/symbols.json` 或币安API)
2. `DataFetchService` 通过 `BinanceClient` 并发获取K线/OI/资金费率，并经 `JdbcDataStore` 持久化到MySQL
3. 各 `Analyzer` 实现对数据执行分析逻辑，返回 `AnalysisReport`
4. `AnalysisScheduler` 定时调度分析器，`ReportWriter` 输出报告到 `reports/` 目录，并在内存缓存最新结果供API查询
5. `AnalysisController` 暴露REST API供手动触发和查询结果

### Data Layer (重要：已从SQLite/Caffeine迁移到MySQL)

`JdbcDataStore` 是唯一的持久化/缓存层，用 `JdbcTemplate` + `ON DUPLICATE KEY UPDATE` 批量upsert。**不再使用 Caffeine 或 DataCacheService**。

`DataFetchService` 的"缓存即数据库"策略：先从MySQL读取，判断数据是否足量且新鲜 (覆盖≥90%的symbol、每个symbol记录数达标、最新K线在2个interval内)，命中则直接返回；否则从币安API拉取并写回MySQL。注意：部分日志字符串仍写着 "SQLite hit"，实际存储是MySQL。

### Analyzer Pattern

所有分析器实现 `Analyzer` 接口。日K线类分析器继承 `AbstractKlineAnalyzer`，模板方法模式：
- `getDefaultParams()` — 从配置读取默认参数
- `resolveParams(requestParams)` — 合并请求参数与默认参数
- `doAnalyze(klineMap, params)` — 核心分析逻辑
- `buildDescription(params)` — 生成报告描述

`AbstractKlineAnalyzer.fetchData()` 默认按 `days + historyBufferDays` 拉取日K线。`Analyzer` 接口支持两种调用：`analyze(symbols)` 用默认配置，`analyze(symbols, params)` 用请求参数覆盖 (未传入的参数仍用配置文件值)。每个分析器通过 `isEnabled()` 对应 `application.yml` 中的 `enabled` 开关。

现有分析器 (名称为中文)：连续上涨、连涨今跌、大幅上涨、连跌今涨、缓涨币、价涨仓增、低位插针、短期急涨、底部吸筹、N日最低、N分钟极值、OI连续上涨、反转做多、山寨币暴涨预警 等。`ShortTermRiseAnalyzer` (短期急涨) 使用分钟级K线，调度上与日K分析器分开处理。

### Scheduling

cron表达式集中在 `application.yml` 的 `binance.schedule.*`：
- `symbol-update` (默认每天6:00) — 更新合约列表
- `daily-analysis` (默认每天8:05) — 执行日常分析 (排除 `ShortTermRiseAnalyzer` 的所有已启用分析器)
- `short-term-analysis` (默认每5分钟) — 执行短期急涨分析，同时刷新日K、OI、资金费率
- `daily-kline-sync` (默认每小时:37) — 同步日K线到MySQL

启动时 `@PostConstruct` (`AnalysisScheduler.init`) 预加载日K线 (及短期急涨所需的分钟K线) 到MySQL。

### REST API (`/api/analysis`)

- `GET /list` — 列出所有分析器名称
- `GET /run/{name}` — 异步执行 (含10秒限流与运行中状态检查)，立即返回任务状态
- `POST /sync/{name}` — 同步执行，支持query参数和JSON body两种传参 (body优先级高于query)
- `GET /latest` / `GET /latest/{name}` — 获取最新分析结果 (内存缓存)
- `GET /params/{name}` — 获取分析器参数说明 (硬编码在 `AnalysisController.getParamSpec`)
- `GET /symbols` / `POST /symbols/refresh` — 查询/刷新合约列表

API路径中直接使用中文分析器名 (如 `/api/analysis/run/短期急涨`)。

### Configuration

所有分析器参数在 `application.yml` 的 `binance.analysis.*` 下配置，每个分析器有 `enabled` 开关。代理 (`binance.proxy`)、并发 (`binance.concurrency`：限流、请求间隔、history-buffer-days、超时)、调度cron、报告目录等也在此配置。`test` profile (文件内 `---` 分隔的第二段) 禁用代理并缩短分析窗口，供测试使用。

## Key Conventions

- 分析器名称使用中文 (如 "短期急涨"、"底部吸筹")，API路径中直接使用中文名
- 报告文件按日期分目录: `reports/yyyyMMdd/yyyyMMdd_HHmmss_类型.txt`
- 新增分析器需要：实现类 + 单元测试 + `application.yml` 配置项 + `AnalysisController.getParamSpec` 中的参数说明；若属日常分析还需在 `AnalysisScheduler.getMaxDailyDays()` 纳入其 days 计算
- 测试使用Mockito mock `DataFetchService`，不依赖外部API/数据库
- 价格字段在模型中以 `String` 存储 (币安原始精度)，计算时转 `double`
