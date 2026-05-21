package com.ba.analyzer.service;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DataCacheService {

    private final Cache<String, Map<String, List<KlineData>>> klineCache;
    private final Cache<String, Map<String, OpenInterestData>> oiCache;
    private final Cache<String, List<OpenInterestData>> oiHistoryCache;
    private final Cache<String, Map<String, List<OpenInterestData>>> oiHistoryBatchCache;

    public DataCacheService(AppProperties appProperties) {
        int ttl = appProperties.getCache().getTtlMinutes();
        int maxSize = appProperties.getCache().getMaxSize();
        this.klineCache = buildCache(ttl, maxSize);
        this.oiCache = buildCache(ttl, maxSize);
        this.oiHistoryCache = buildCache(ttl, maxSize);
        this.oiHistoryBatchCache = buildCache(ttl, maxSize);
    }

    private static <V> Cache<String, V> buildCache(int ttlMinutes, int maxSize) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .build();
    }

    public Map<String, List<KlineData>> getKlines(String cacheKey) {
        return klineCache.getIfPresent(cacheKey);
    }

    public void putKlines(String cacheKey, Map<String, List<KlineData>> data) {
        klineCache.put(cacheKey, data);
        log.info("Cached klines data: key={}, symbols={}", cacheKey, data.size());
    }

    public Map<String, OpenInterestData> getOpenInterest(String cacheKey) {
        return oiCache.getIfPresent(cacheKey);
    }

    public void putOpenInterest(String cacheKey, Map<String, OpenInterestData> data) {
        oiCache.put(cacheKey, data);
    }

    public List<OpenInterestData> getOiHistory(String cacheKey) {
        return oiHistoryCache.getIfPresent(cacheKey);
    }

    public Map<String, List<OpenInterestData>> getOiHistoryBatch(String cacheKey) {
        return oiHistoryBatchCache.getIfPresent(cacheKey);
    }

    public void putOiHistory(String cacheKey, List<OpenInterestData> data) {
        oiHistoryCache.put(cacheKey, data);
    }

    public void putOiHistoryBatch(String cacheKey, Map<String, List<OpenInterestData>> data) {
        oiHistoryBatchCache.put(cacheKey, data);
    }

    public void clear() {
        klineCache.invalidateAll();
        oiCache.invalidateAll();
        oiHistoryCache.invalidateAll();
        oiHistoryBatchCache.invalidateAll();
        log.info("All caches cleared");
    }

    public long size() {
        return klineCache.estimatedSize() + oiCache.estimatedSize()
                + oiHistoryCache.estimatedSize() + oiHistoryBatchCache.estimatedSize();
    }
}
