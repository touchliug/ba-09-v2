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
    private final JdbcDataStore dataStore;

    public Map<String, List<KlineData>> fetchDailyKlines(List<String> symbols, int days) {
        return fetchKlinesByInterval(symbols, "1d", days);
    }

    public Map<String, List<KlineData>> fetchHourlyKlines(List<String> symbols, int hours) {
        return fetchKlinesByInterval(symbols, "1h", hours);
    }

    public Map<String, List<KlineData>> fetchKlinesByInterval(List<String> symbols, String interval, int period) {
        Map<String, List<KlineData>> stored = dataStore.getKlinesBatch(symbols, interval, period + 1);
        long intervalMs = intervalToMillis(interval);
        long now = System.currentTimeMillis();
        boolean hasEnoughData = stored.size() >= symbols.size() * 0.9
                && stored.values().stream().allMatch(k -> k.size() >= period)
                && stored.values().stream().allMatch(k -> {
                    long latestOpenTime = k.get(k.size() - 1).getOpenTime();
                    return now - latestOpenTime < intervalMs * 2;
                });

        if (hasEnoughData) {
            log.info("SQLite hit for {} klines: {} symbols, {} periods", interval, stored.size(), period);
            return trimKlinesByPeriod(stored, period);
        }

        log.info("Fetching {} klines from API for {} symbols, {} periods (stale or insufficient)", interval, symbols.size(), period);
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

    private long intervalToMillis(String interval) {
        return switch (interval) {
            case "1m" -> 60_000L;
            case "3m" -> 180_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "30m" -> 1_800_000L;
            case "1h" -> 3_600_000L;
            case "2h" -> 7_200_000L;
            case "4h" -> 14_400_000L;
            case "6h" -> 21_600_000L;
            case "8h" -> 28_800_000L;
            case "12h" -> 43_200_000L;
            case "1d" -> 86_400_000L;
            default -> 86_400_000L;
        };
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
            if (oi != null) result.put(symbol, oi);
            return symbol;
        });
        log.info("Fetched open interest for {} symbols", result.size());
        return result;
    }

    public void syncOpenInterest(List<String> symbols) {
        Map<String, OpenInterestData> oiMap = fetchOpenInterest(symbols);
        for (Map.Entry<String, OpenInterestData> entry : oiMap.entrySet()) {
            dataStore.saveOpenInterest(entry.getKey(), List.of(entry.getValue()));
        }
        log.info("Synced open interest for {} symbols to DB", oiMap.size());
    }

    public void syncFundingRates(List<String> symbols) {
        log.info("Syncing funding rates for {} symbols", symbols.size());
        binanceClient.executeConcurrent(symbols, symbol -> {
            var rates = binanceClient.getFundingRates(symbol, 10);
            if (!rates.isEmpty()) dataStore.saveFundingRates(symbol, rates);
            return symbol;
        });
        log.info("Funding rate sync done");
    }

    public List<OpenInterestData> fetchOpenInterestHistory(String symbol, int days) {
        List<OpenInterestData> stored = dataStore.getOpenInterestHistory(symbol, days);
        if (stored.size() >= days) {
            log.debug("SQLite hit for OI history: {} ({} records)", symbol, stored.size());
            return stored;
        }
        List<OpenInterestData> fetched = binanceClient.getOpenInterestHistory(symbol, "1d", days);
        if (!fetched.isEmpty()) dataStore.saveOpenInterest(symbol, fetched);
        return fetched;
    }

    public Map<String, List<OpenInterestData>> fetchOpenInterestHistoryBatch(List<String> symbols, int days) {
        Map<String, List<OpenInterestData>> stored = dataStore.getOpenInterestBatch(symbols, days);
        if (stored.size() >= symbols.size() * 0.9) {
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