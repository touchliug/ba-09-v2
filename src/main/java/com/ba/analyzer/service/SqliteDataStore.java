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
}