# SQLite 持久化缓存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Caffeine in-memory cache with SQLite persistent storage, deduplicating data by time period and only fetching missing data from the Binance API.

**Architecture:** New `SqliteDataStore` class handles all SQLite operations (DDL, CRUD). `DataFetchService` is modified to use SQLite instead of Caffeine — checking what data exists, fetching only gaps, and writing new data. `DataCacheService` is deleted. `AnalysisScheduler` is updated to remove cache references.

**Tech Stack:** Java 21, SQLite (via xerial sqlite-jdbc 3.45.3.0), Spring Boot 3.2.x

---

## File Structure

```
src/main/java/com/ba/analyzer/
├── service/
│   ├── SqliteDataStore.java    — NEW: SQLite connection, DDL, CRUD operations
│   ├── DataFetchService.java   — MODIFY: replace Caffeine with SqliteDataStore
│   └── DataCacheService.java   — DELETE
├── scheduler/
│   └── AnalysisScheduler.java  — MODIFY: remove DataCacheService dependency
pom.xml                         — MODIFY: add sqlite-jdbc, remove caffeine
```

---

## Task 1: Add SQLite dependency, remove Caffeine

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Replace caffeine with sqlite-jdbc in pom.xml**

Replace the caffeine dependency block:
```xml
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
```

With:
```xml
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>3.45.3.0</version>
        </dependency>
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: Compilation errors in DataCacheService and DataFetchService (expected — we'll fix in next tasks)

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: replace caffeine with sqlite-jdbc dependency"
```

---

## Task 2: Create SqliteDataStore

**Files:**
- Create: `src/main/java/com/ba/analyzer/service/SqliteDataStore.java`

- [ ] **Step 1: Create SqliteDataStore with connection init and DDL**

```java
package com.ba.analyzer.service;

import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class SqliteDataStore {

    private static final String DB_PATH = "./data/analyzer.db";
    private Connection connection;
    private final ReentrantLock writeLock = new ReentrantLock();

    @PostConstruct
    public void init() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA busy_timeout=5000");
                createTables(stmt);
            }
            log.info("SQLite initialized: {}", DB_PATH);
        } catch (SQLException e) {
            log.error("Failed to initialize SQLite", e);
            throw new RuntimeException(e);
        }
    }

    private void createTables(Statement stmt) throws SQLException {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS klines (
                symbol TEXT NOT NULL,
                interval TEXT NOT NULL,
                open_time INTEGER NOT NULL,
                open TEXT, high TEXT, low TEXT, close TEXT,
                volume TEXT, close_time INTEGER,
                quote_asset_volume TEXT, number_of_trades INTEGER,
                taker_buy_base TEXT, taker_buy_quote TEXT,
                PRIMARY KEY (symbol, interval, open_time)
            )""");
        stmt.execute("""
            CREATE INDEX IF NOT EXISTS idx_klines_lookup
            ON klines(symbol, interval, open_time DESC)""");
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS open_interest (
                symbol TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                open_interest TEXT,
                sum_open_interest TEXT,
                sum_open_interest_value TEXT,
                PRIMARY KEY (symbol, timestamp)
            )""");
        stmt.execute("""
            CREATE INDEX IF NOT EXISTS idx_oi_lookup
            ON open_interest(symbol, timestamp DESC)""");
    }

    @PreDestroy
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("SQLite connection closed");
            }
        } catch (SQLException e) {
            log.warn("Error closing SQLite", e);
        }
    }
}
```

- [ ] **Step 2: Add K-line CRUD methods**

Append to `SqliteDataStore.java`:

```java
    public void saveKlines(String symbol, String interval, List<KlineData> klines) {
        if (klines == null || klines.isEmpty()) return;
        String sql = """
            INSERT OR REPLACE INTO klines
            (symbol, interval, open_time, open, high, low, close, volume,
             close_time, quote_asset_volume, number_of_trades, taker_buy_base, taker_buy_quote)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        writeLock.lock();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (KlineData k : klines) {
                ps.setString(1, symbol);
                ps.setString(2, interval);
                ps.setLong(3, k.getOpenTime());
                ps.setString(4, k.getOpen());
                ps.setString(5, k.getHigh());
                ps.setString(6, k.getLow());
                ps.setString(7, k.getClose());
                ps.setString(8, k.getVolume());
                ps.setLong(9, k.getCloseTime());
                ps.setString(10, k.getQuoteAssetVolume());
                ps.setInt(11, k.getNumberOfTrades());
                ps.setString(12, k.getTakerBuyBaseAssetVolume());
                ps.setString(13, k.getTakerBuyQuoteAssetVolume());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.error("Failed to save klines for {}", symbol, e);
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        } finally {
            writeLock.unlock();
        }
    }

    public List<KlineData> getKlines(String symbol, String interval, int limit) {
        String sql = "SELECT * FROM klines WHERE symbol=? AND interval=? ORDER BY open_time DESC LIMIT ?";
        List<KlineData> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, interval);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(mapKline(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get klines for {}", symbol, e);
        }
        Collections.reverse(result);
        return result;
    }

    public Map<String, List<KlineData>> getKlinesBatch(List<String> symbols, String interval, int limit) {
        Map<String, List<KlineData>> result = new HashMap<>();
        for (String symbol : symbols) {
            List<KlineData> klines = getKlines(symbol, interval, limit);
            if (!klines.isEmpty()) {
                result.put(symbol, klines);
            }
        }
        return result;
    }

    public long getLatestKlineTime(String symbol, String interval) {
        String sql = "SELECT MAX(open_time) FROM klines WHERE symbol=? AND interval=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, interval);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            log.error("Failed to get latest kline time for {}", symbol, e);
        }
        return 0;
    }

    private KlineData mapKline(ResultSet rs) throws SQLException {
        KlineData k = new KlineData();
        k.setOpenTime(rs.getLong("open_time"));
        k.setOpen(rs.getString("open"));
        k.setHigh(rs.getString("high"));
        k.setLow(rs.getString("low"));
        k.setClose(rs.getString("close"));
        k.setVolume(rs.getString("volume"));
        k.setCloseTime(rs.getLong("close_time"));
        k.setQuoteAssetVolume(rs.getString("quote_asset_volume"));
        k.setNumberOfTrades(rs.getInt("number_of_trades"));
        k.setTakerBuyBaseAssetVolume(rs.getString("taker_buy_base"));
        k.setTakerBuyQuoteAssetVolume(rs.getString("taker_buy_quote"));
        return k;
    }
```

- [ ] **Step 3: Add Open Interest CRUD methods**

Append to `SqliteDataStore.java`:

```java
    public void saveOpenInterest(String symbol, List<OpenInterestData> dataList) {
        if (dataList == null || dataList.isEmpty()) return;
        String sql = """
            INSERT OR REPLACE INTO open_interest
            (symbol, timestamp, open_interest, sum_open_interest, sum_open_interest_value)
            VALUES (?,?,?,?,?)""";
        writeLock.lock();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (OpenInterestData oi : dataList) {
                long ts = oi.getTimestamp() > 0 ? oi.getTimestamp() : oi.getTime();
                ps.setString(1, symbol);
                ps.setLong(2, ts);
                ps.setString(3, oi.getOpenInterest());
                ps.setString(4, oi.getSumOpenInterest());
                ps.setString(5, oi.getSumOpenInterestValue());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.error("Failed to save OI for {}", symbol, e);
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        } finally {
            writeLock.unlock();
        }
    }

    public List<OpenInterestData> getOpenInterestHistory(String symbol, int limit) {
        String sql = "SELECT * FROM open_interest WHERE symbol=? ORDER BY timestamp DESC LIMIT ?";
        List<OpenInterestData> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(mapOi(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get OI history for {}", symbol, e);
        }
        Collections.reverse(result);
        return result;
    }

    public Map<String, List<OpenInterestData>> getOpenInterestBatch(List<String> symbols, int limit) {
        Map<String, List<OpenInterestData>> result = new HashMap<>();
        for (String symbol : symbols) {
            List<OpenInterestData> history = getOpenInterestHistory(symbol, limit);
            if (!history.isEmpty()) {
                result.put(symbol, history);
            }
        }
        return result;
    }

    private OpenInterestData mapOi(ResultSet rs) throws SQLException {
        OpenInterestData oi = new OpenInterestData();
        oi.setSymbol(rs.getString("symbol"));
        oi.setTimestamp(rs.getLong("timestamp"));
        oi.setOpenInterest(rs.getString("open_interest"));
        oi.setSumOpenInterest(rs.getString("sum_open_interest"));
        oi.setSumOpenInterestValue(rs.getString("sum_open_interest_value"));
        return oi;
    }
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ba/analyzer/service/SqliteDataStore.java
git commit -m "feat: add SqliteDataStore with kline and OI persistence"
```

---

## Task 3: Rewrite DataFetchService to use SqliteDataStore

**Files:**
- Modify: `src/main/java/com/ba/analyzer/service/DataFetchService.java`

- [ ] **Step 1: Replace DataFetchService contents**

Replace the entire file with:

```java
package com.ba.analyzer.service;

import com.ba.analyzer.client.BinanceClient;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataFetchService {

    private final BinanceClient binanceClient;
    private final SqliteDataStore dataStore;

    public Map<String, List<KlineData>> fetchDailyKlines(List<String> symbols, int days) {
        return fetchKlinesByInterval(symbols, "1d", days);
    }

    public Map<String, List<KlineData>> fetchHourlyKlines(List<String> symbols, int hours) {
        return fetchKlinesByInterval(symbols, "1h", hours);
    }

    public Map<String, List<KlineData>> fetchKlinesByInterval(List<String> symbols, String interval, int period) {
        Map<String, List<KlineData>> stored = dataStore.getKlinesBatch(symbols, interval, period + 1);
        boolean hasEnoughData = stored.size() >= symbols.size() * 0.9
                && stored.values().stream().allMatch(k -> k.size() >= period);

        if (hasEnoughData) {
            log.info("SQLite hit for {} klines: {} symbols, {} periods", interval, stored.size(), period);
            return trimKlinesByPeriod(stored, period);
        }

        log.info("Fetching {} klines from API for {} symbols, {} periods", interval, symbols.size(), period);
        ConcurrentMap<String, List<KlineData>> result = new ConcurrentHashMap<>();

        binanceClient.executeConcurrent(symbols, symbol -> {
            List<KlineData> klines = binanceClient.getKlines(symbol, interval, period + 1);
            if (!klines.isEmpty()) {
                result.put(symbol, klines);
                dataStore.saveKlines(symbol, interval, klines);
            }
            return symbol;
        });

        log.info("Fetched and stored {} klines for {} symbols", interval, result.size());
        return result;
    }

    private Map<String, List<KlineData>> trimKlinesByPeriod(Map<String, List<KlineData>> klineMap, int period) {
        Map<String, List<KlineData>> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            List<KlineData> klines = entry.getValue();
            if (klines.size() <= period) {
                result.put(entry.getKey(), klines);
            } else {
                result.put(entry.getKey(), klines.subList(klines.size() - period, klines.size()));
            }
        }
        return result;
    }

    public Map<String, OpenInterestData> fetchOpenInterest(List<String> symbols) {
        log.info("Fetching current open interest for {} symbols", symbols.size());
        ConcurrentMap<String, OpenInterestData> result = new ConcurrentHashMap<>();

        binanceClient.executeConcurrent(symbols, symbol -> {
            OpenInterestData oi = binanceClient.getOpenInterest(symbol);
            if (oi != null) {
                result.put(symbol, oi);
            }
            return symbol;
        });

        log.info("Fetched open interest for {} symbols", result.size());
        return result;
    }

    public List<OpenInterestData> fetchOpenInterestHistory(String symbol, int days) {
        List<OpenInterestData> stored = dataStore.getOpenInterestHistory(symbol, days);
        if (stored.size() >= days) {
            log.debug("SQLite hit for OI history: {} ({} records)", symbol, stored.size());
            return stored;
        }

        List<OpenInterestData> fetched = binanceClient.getOpenInterestHistory(symbol, "1d", days);
        if (!fetched.isEmpty()) {
            dataStore.saveOpenInterest(symbol, fetched);
        }
        return fetched;
    }

    public Map<String, List<OpenInterestData>> fetchOpenInterestHistoryBatch(List<String> symbols, int days) {
        Map<String, List<OpenInterestData>> stored = dataStore.getOpenInterestBatch(symbols, days);
        boolean hasEnoughData = stored.size() >= symbols.size() * 0.9;

        if (hasEnoughData) {
            log.info("SQLite hit for OI history batch: {} symbols", stored.size());
            return stored;
        }

        log.info("Fetching OI history from API for {} symbols, {} days", symbols.size(), days);
        ConcurrentMap<String, List<OpenInterestData>> result = new ConcurrentHashMap<>();

        binanceClient.executeConcurrent(symbols, symbol -> {
            List<OpenInterestData> oiHistory = binanceClient.getOpenInterestHistory(symbol, "1d", days);
            if (!oiHistory.isEmpty()) {
                result.put(symbol, oiHistory);
                dataStore.saveOpenInterest(symbol, oiHistory);
            }
            return symbol;
        });

        log.info("Fetched and stored OI history for {} symbols", result.size());
        return result;
    }

    public void preloadDailyKlines(List<String> symbols, int maxDays) {
        Map<String, List<KlineData>> stored = dataStore.getKlinesBatch(symbols, "1d", maxDays);
        if (stored.size() >= symbols.size() * 0.9) {
            log.info("Preload skipped, SQLite has daily klines for {} symbols", stored.size());
            return;
        }
        log.info("Preloading daily klines for {} symbols, {} days", symbols.size(), maxDays);
        fetchDailyKlines(symbols, maxDays);
    }

    public void preloadHourlyKlines(List<String> symbols, int maxHours) {
        Map<String, List<KlineData>> stored = dataStore.getKlinesBatch(symbols, "1h", maxHours);
        if (stored.size() >= symbols.size() * 0.9) {
            log.info("Preload skipped, SQLite has hourly klines for {} symbols", stored.size());
            return;
        }
        log.info("Preloading hourly klines for {} symbols, {} hours", symbols.size(), maxHours);
        fetchHourlyKlines(symbols, maxHours);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/ba/analyzer/service/DataFetchService.java
git commit -m "refactor: rewrite DataFetchService to use SqliteDataStore"
```

---

## Task 4: Update AnalysisScheduler and delete DataCacheService

**Files:**
- Modify: `src/main/java/com/ba/analyzer/scheduler/AnalysisScheduler.java`
- Delete: `src/main/java/com/ba/analyzer/service/DataCacheService.java`

- [ ] **Step 1: Remove DataCacheService from AnalysisScheduler**

In `AnalysisScheduler.java`:
- Remove the import: `import com.ba.analyzer.service.DataCacheService;`
- Remove the field: `private final DataCacheService dataCacheService;`
- Remove from constructor parameter and assignment
- In `init()` method, remove the line: `dataCacheService.clear();`

The constructor should become:
```java
    public AnalysisScheduler(SymbolService symbolService, ReportWriter reportWriter,
                             AppProperties appProperties, DataFetchService dataFetchService,
                             List<Analyzer> analyzers,
                             @Qualifier("asyncAnalysisExecutor") ExecutorService asyncExecutor) {
        this.symbolService = symbolService;
        this.reportWriter = reportWriter;
        this.appProperties = appProperties;
        this.dataFetchService = dataFetchService;
        this.analyzers = analyzers;
        this.asyncExecutor = asyncExecutor;
    }
```

- [ ] **Step 2: Delete DataCacheService.java**

```bash
git rm src/main/java/com/ba/analyzer/service/DataCacheService.java
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Run tests**

Run: `mvn test -q`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove DataCacheService, AnalysisScheduler uses DataFetchService only"
```

---

## Task 5: Remove Caffeine config from AppProperties

**Files:**
- Modify: `src/main/java/com/ba/analyzer/config/AppProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Remove cache inner class from AppProperties if unused**

Check if `AppProperties.Cache` (with `ttlMinutes` and `maxSize`) is referenced anywhere besides the deleted `DataCacheService`. If not, remove the `Cache` inner class and its field from `AppProperties`.

- [ ] **Step 2: Remove cache config from application.yml**

Remove from default profile:
```yaml
  cache:
    ttl-minutes: 7
    max-size: 500
```

Remove from test profile:
```yaml
  cache:
    ttl-minutes: 1
```

- [ ] **Step 3: Verify compilation and tests**

Run: `mvn compile test -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: remove unused Caffeine cache configuration"
```

---

## Task 6: Integration verification

- [ ] **Step 1: Start the application**

Run: `mvn spring-boot:run`
Expected: Application starts, logs show "SQLite initialized: ./data/analyzer.db"

- [ ] **Step 2: Verify database file created**

Check: `./data/analyzer.db` exists after startup.

- [ ] **Step 3: Trigger an analysis and verify data persists**

```bash
curl -X POST http://localhost:8080/api/analysis/sync/短期急涨
```

Expected: Analysis runs, results returned. Verify SQLite has data:
```bash
sqlite3 ./data/analyzer.db "SELECT COUNT(*) FROM klines;"
```
Should return > 0.

- [ ] **Step 4: Restart application and verify data survives**

Stop and restart the app. Run the same analysis — should be faster (SQLite hit, fewer API calls). Check logs for "SQLite hit" messages.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "feat: complete SQLite cache migration - data persists across restarts"
```
