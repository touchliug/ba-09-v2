package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.service.DataFetchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * OI多周期分析器 - 监控不同时间段的持仓量变化
 * 检测5m/1h/6h/24h/30d的OI异动, 发现主力建仓/出货痕迹
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OIMultiTFAnalyzer implements Analyzer {

    private final DataFetchService dataFetchService;
    private final AppProperties appProperties;

    @Override public String getName() { return "OI多周期"; }
    @Override public boolean isEnabled() { return true; }

    @Override
    public String getDescription() {
        return "监控OI在5分钟/1小时/6小时/24小时/30天的变化率, 发现主力建仓/出货痕迹";
    }

    @Override
    public AnalysisReport analyze(List<String> symbols) {
        return doAnalyze(symbols);
    }

    @Override
    public AnalysisReport analyze(List<String> symbols, Map<String, Object> params) {
        return doAnalyze(symbols);
    }

    private AnalysisReport doAnalyze(List<String> symbols) {
        // Fetch OI history for 30 days
        Map<String, List<OpenInterestData>> oiMap = dataFetchService.fetchOpenInterestHistoryBatch(symbols, 30);
        long now = System.currentTimeMillis();
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (String symbol : symbols) {
            List<OpenInterestData> oiList = oiMap.get(symbol);
            if (oiList == null || oiList.size() < 2) continue;

            double currentOi = oiList.get(oiList.size() - 1).getOpenInterestValue();
            if (currentOi <= 0) continue;

            // Calculate OI changes at different timeframes
            double oi5m = calcChange(oiList, now - 5 * 60_000L);
            double oi1h = calcChange(oiList, now - 60 * 60_000L);
            double oi6h = calcChange(oiList, now - 6 * 60 * 60_000L);
            double oi24h = calcChange(oiList, now - 24 * 60 * 60_000L);
            double oi30d = 0;
            if (oiList.size() >= 2) {
                double firstOi = oiList.get(0).getOpenInterestValue();
                if (firstOi > 0) oi30d = (currentOi - firstOi) / firstOi * 100;
            }

            // Flag anomalies: OI up >10% while price didn't move much (stealth accumulation)
            // or OI down >10% (distribution)
            boolean anomaly = Math.abs(oi6h) > 8 || Math.abs(oi24h) > 15 || Math.abs(oi30d) > 30;
            if (!anomaly) continue;

            String direction = oi24h > 10 ? "主力建仓" : oi24h < -10 ? "主力出货" : "异动";

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(currentOi)
                    .changePercent(oi24h)
                    .detail(String.format("%s | OI变化: 5m%+.1f%% 1h%+.1f%% 6h%+.1f%% 24h%+.1f%% 30d%+.1f%% | 当前OI=%.0f",
                            direction, oi5m, oi1h, oi6h, oi24h, oi30d, currentOi))
                    .build());
        }

        // Sort by biggest OI change (absolute)
        matched.sort(Comparator.comparingDouble(
                (AnalysisReport.CoinAnalysis c) -> Math.abs(c.getChangePercent())).reversed());

        return AnalysisReport.builder()
                .analysisType(getName())
                .analysisTime(LocalDateTime.now())
                .description("OI多周期异动监控")
                .coins(matched)
                .totalAnalyzed(symbols.size())
                .matchedCount(matched.size())
                .usedParams(Map.of("source", "config"))
                .build();
    }

    private double calcChange(List<OpenInterestData> oiList, long sinceTime) {
        OpenInterestData closest = null;
        for (int i = oiList.size() - 1; i >= 0; i--) {
            if (oiList.get(i).getTimestamp() <= sinceTime
                    || oiList.get(i).getTime() <= sinceTime) {
                closest = oiList.get(i);
                break;
            }
        }
        if (closest == null) return 0;
        double pastOi = closest.getOpenInterestValue();
        if (pastOi <= 0) return 0;
        double currentOi = oiList.get(oiList.size() - 1).getOpenInterestValue();
        return (currentOi - pastOi) / pastOi * 100;
    }
}
