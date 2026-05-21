# SQLite 持久化缓存设计规格

## 目标

将现有 Caffeine 内存缓存替换为 SQLite 持久化存储，实现数据按时间段去重、重启不丢失、减少不必要的 API 调用。

## 技术方案

- 使用 `org.xerial:sqlite-jdbc` 直接通过 JDBC 操作 SQLite
- 不引入 JPA/MyBatis，保持轻量
- 数据库文件：`./data/analyzer.db`
- 开启 WAL 模式提升并发读写性能

## 数据库表结构

### klines 表

存储 K 线数据，按 (symbol, interval, open_time) 唯一去重。已收盘 K 线一旦写入不再更新，未收盘 K 线通过 INSERT OR REPLACE 覆盖。

```sql
CREATE TABLE IF NOT EXISTS klines (
    symbol TEXT NOT NULL,
    interval TEXT NOT NULL,
    open_time INTEGER NOT NULL,
    open TEXT,
    high TEXT,
    low TEXT,
    close TEXT,
    volume TEXT,
    close_time INTEGER,
    quote_asset_volume TEXT,
    number_of_trades INTEGER,
    taker_buy_base TEXT,
    taker_buy_quote TEXT,
    created_at INTEGER DEFAULT (strftime('%s','now')),
    PRIMARY KEY (symbol, interval, open_time)
);

CREATE INDEX IF NOT EXISTS idx_klines_lookup
    ON klines(symbol, interval, open_time DESC);
```

### open_interest 表

存储持仓量快照数据，按 (symbol, timestamp) 唯一去重。

```sql
CREATE TABLE IF NOT EXISTS open_interest (
    symbol TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    open_interest TEXT,
    sum_open_interest TEXT,
    sum_open_interest_value TEXT,
    created_at INTEGER DEFAULT (strftime('%s','now')),
    PRIMARY KEY (symbol, timestamp)
);

CREATE INDEX IF NOT EXISTS idx_oi_lookup
    ON open_interest(symbol, timestamp DESC);
```

## 数据流

### 写入流程（新数据入库）

1. `DataFetchService` 从币安 API 获取数据
2. 立即写入 SQLite（INSERT OR REPLACE）
3. 返回数据给调用方

### 读取流程（智能增量拉取）

1. 分析器请求 N 天日 K 数据
2. `DataFetchService` 查询 SQLite 已有哪些该 symbol + interval 的 K 线
3. 判断缺失：
   - 已收盘 K 线（open_time < 当前周期起始时间）：如果 SQLite 有就不再拉取
   - 当前未收盘 K 线：始终从 API 拉取最新
4. 只从 API 拉取缺失部分 → 写入 SQLite
5. 从 SQLite 读取完整 N 天数据返回

### 持仓量数据流

- 当前持仓量：每次从 API 拉取（实时数据，不缓存）
- 持仓量历史：查 SQLite 已有记录，只拉取时间范围内缺失的部分

## 文件变更

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `SqliteDataStore.java` | SQLite 操作封装（建表、CRUD） |
| 修改 | `DataFetchService.java` | 替换 Caffeine 调用为 SQLite 读写 |
| 修改 | `pom.xml` | 添加 sqlite-jdbc 依赖 |
| 修改 | `application.yml` | 无需额外配置（路径硬编码为 ./data/analyzer.db） |
| 删除 | `DataCacheService.java` | 不再需要内存缓存 |

## 新增类：SqliteDataStore

职责：
- 应用启动时初始化数据库连接和表结构
- 提供 K 线数据的批量写入和按条件查询
- 提供持仓量数据的批量写入和按条件查询
- 连接池：单连接 + WAL 模式（SQLite 单写多读）

接口方法：
```java
// K线
void saveKlines(String symbol, String interval, List<KlineData> klines);
List<KlineData> getKlines(String symbol, String interval, int limit);
List<KlineData> getKlinesSince(String symbol, String interval, long sinceOpenTime);
boolean hasKline(String symbol, String interval, long openTime);

// 持仓量
void saveOpenInterest(String symbol, List<OpenInterestData> data);
List<OpenInterestData> getOpenInterest(String symbol, long sinceTimestamp, int limit);
OpenInterestData getLatestOpenInterest(String symbol);

// 批量操作
Map<String, List<KlineData>> getKlinesBatch(List<String> symbols, String interval, int limit);
Map<String, List<OpenInterestData>> getOpenInterestBatch(List<String> symbols, long sinceTimestamp);
```

## 依赖

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.3.0</version>
</dependency>
```

## 错误处理

- 数据库文件不存在：自动创建
- 写入失败：记录日志，不影响分析流程（降级为纯 API 模式）
- 数据库损坏：删除 db 文件重建（极端情况）

## 性能考量

- WAL 模式：允许并发读取，写入不阻塞读取
- 批量写入使用事务（一次 commit 多条 INSERT）
- 索引覆盖主要查询模式（symbol + interval + open_time DESC）
- 数据量预估：500 symbols × 30 days × 1d interval = 15000 行（极小）
