package com.ba.analyzer.service;

import com.ba.analyzer.client.BinanceClient;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.List;
import java.util.Map;

/**
 * 数据获取服务
 * 封装K线数据和持仓量数据的批量获取逻辑
 * 支持按日/按小时获取K线，并发请求多个交易对的数据
 * 优先从缓存获取数据，缓存未命中时才请求API
 * K线缓存使用统一key(kline:1d:all)，存储最大天数数据，分析器按需截取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataFetchService {

    private final BinanceClient binanceClient;
    private final DataCacheService cacheService;

    public Map<String, List<KlineData>> fetchDailyKlines(List<String> symbols, int days) {
        return fetchKlinesByInterval(symbols, "1d", days);
    }

    public Map<String, List<KlineData>> fetchHourlyKlines(List<String> symbols, int hours) {
        return fetchKlinesByInterval(symbols, "1h", hours);
    }

    public Map<String, List<KlineData>> fetchKlinesByInterval(List<String> symbols, String interval, int period) {
        String cacheKey = "kline:" + interval + ":all";
        Map<String, List<KlineData>> cached = cacheService.getKlines(cacheKey);
        boolean hasEnoughData = cached != null && cached.size() >= symbols.size() * 0.9;
        if (hasEnoughData) {
            boolean allHaveEnoughPeriod = cached.values().stream()
                    .allMatch(klines -> klines.size() >= period);
            if (allHaveEnoughPeriod) {
                log.info("Cache hit for {} klines: {} symbols, requested {} periods", interval, cached.size(), period);
                return trimKlinesByPeriod(cached, period);
            }
            log.info("Cache exists but data insufficient for {} periods, re-fetching", period);
        }

        log.info("Cache miss, fetching {} klines for {} symbols, {} periods", interval, symbols.size(), period);
        ConcurrentMap<String, List<KlineData>> result = new ConcurrentHashMap<>();

        binanceClient.executeConcurrent(symbols, symbol -> {
            List<KlineData> klines = binanceClient.getKlines(symbol, interval, period + 1);
            if (!klines.isEmpty()) {
                result.put(symbol, klines);
            }
            return symbol;
        });

        cacheService.putKlines(cacheKey, result);
        log.info("Fetched {} klines for {} symbols", interval, result.size());
        return result;
    }

    private ConcurrentMap<String, List<KlineData>> trimKlinesByPeriod(Map<String, List<KlineData>> klineMap, int period) {
        ConcurrentMap<String, List<KlineData>> result = new ConcurrentHashMap<>();
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
        String cacheKey = "oi:current";
        Map<String, OpenInterestData> cached = cacheService.getOpenInterest(cacheKey);
        if (cached != null && cached.size() >= symbols.size() * 0.9) {
            log.info("Cache hit for open interest: {} symbols", cached.size());
            return cached;
        }

        log.info("Cache miss, fetching open interest for {} symbols", symbols.size());
        ConcurrentMap<String, OpenInterestData> result = new ConcurrentHashMap<>();

        binanceClient.executeConcurrent(symbols, symbol -> {
            OpenInterestData oi = binanceClient.getOpenInterest(symbol);
            if (oi != null) {
                result.put(symbol, oi);
            }
            return symbol;
        });

        cacheService.putOpenInterest(cacheKey, result);
        log.info("Fetched open interest for {} symbols", result.size());
        return result;
    }

    public List<OpenInterestData> fetchOpenInterestHistory(String symbol, int days) {
        String cacheKey = "oi:hist:" + symbol + ":" + days;
        List<OpenInterestData> cached = cacheService.getOiHistory(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<OpenInterestData> result = binanceClient.getOpenInterestHistory(symbol, "1d", days);
        if (!result.isEmpty()) {
            cacheService.putOiHistory(cacheKey, result);
        }
        return result;
    }

    public Map<String, List<OpenInterestData>> fetchOpenInterestHistoryBatch(List<String> symbols, int days) {
        String cacheKey = "oi:hist:batch:" + days + ":" + Objects.hash(symbols);
        Map<String, List<OpenInterestData>> cached = cacheService.getOiHistoryBatch(cacheKey);
        if (cached != null && cached.size() >= symbols.size() * 0.9) {
            log.info("Cache hit for OI history batch: {} symbols", cached.size());
            return cached;
        }

        log.info("Cache miss, fetching OI history for {} symbols, {} days", symbols.size(), days);
        ConcurrentMap<String, List<OpenInterestData>> result = new ConcurrentHashMap<>();

        binanceClient.executeConcurrent(symbols, symbol -> {
            List<OpenInterestData> oiHistory = binanceClient.getOpenInterestHistory(symbol, "1d", days);
            if (!oiHistory.isEmpty()) {
                result.put(symbol, oiHistory);
            }
            return symbol;
        });

        cacheService.putOiHistoryBatch(cacheKey, result);
        log.info("Fetched OI history for {} symbols", result.size());
        return result;
    }

    public void preloadDailyKlines(List<String> symbols, int maxDays) {
        String cacheKey = "kline:1d:all";
        Map<String, List<KlineData>> cached = cacheService.getKlines(cacheKey);
        if (cached != null && cached.size() == symbols.size()) {
            log.info("Preload skipped, cache already exists for daily klines: {} symbols", cached.size());
            return;
        }
        log.info("Preloading daily klines for {} symbols, {} days", symbols.size(), maxDays);
        fetchDailyKlines(symbols, maxDays);
    }

    public void preloadHourlyKlines(List<String> symbols, int maxHours) {
        String cacheKey = "kline:1h:all";
        Map<String, List<KlineData>> cached = cacheService.getKlines(cacheKey);
        if (cached != null && cached.size() == symbols.size()) {
            log.info("Preload skipped, cache already exists for hourly klines: {} symbols", cached.size());
            return;
        }
        log.info("Preloading hourly klines for {} symbols, {} hours", symbols.size(), maxHours);
        fetchHourlyKlines(symbols, maxHours);
    }

}
