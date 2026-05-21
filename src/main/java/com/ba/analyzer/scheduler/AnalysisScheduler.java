package com.ba.analyzer.scheduler;

import com.ba.analyzer.analysis.Analyzer;
import com.ba.analyzer.analysis.ShortTermRiseAnalyzer;
import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.report.ReportWriter;
import com.ba.analyzer.service.DataFetchService;
import com.ba.analyzer.service.SymbolService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 定时调度器
 * 管理三个定时任务：
 * 1. 每天早上6点更新USDT合约列表
 * 2. 每天早上8:05执行日常分析（Feature 2-8，排除短期急涨）
 * 3. 每10分钟执行短期急涨分析（Feature 9）
 * 同时缓存最新的分析结果供Web接口查询
 * 支持异步执行分析器，避免Web接口阻塞
 * 日常分析前预加载K线数据到缓存，多分析器共享数据
 */
@Slf4j
@Component
public class AnalysisScheduler {

    private final SymbolService symbolService;
    private final ReportWriter reportWriter;
    private final AppProperties appProperties;
    private final DataFetchService dataFetchService;
    private final List<Analyzer> analyzers;
    private final ExecutorService asyncExecutor;

    private final Map<String, AnalysisReport> latestReports = new ConcurrentHashMap<>();
    private final Set<String> runningAnalyzers = ConcurrentHashMap.newKeySet();

    private volatile List<String> cachedSymbols = List.of();
    private final Object symbolsLock = new Object();

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

    @PostConstruct
    public void init(){
        List<String> symbols = getSymbols();
        int maxDays = getMaxDailyDays();
        log.info("Preloading daily klines for {} symbols, {} days", symbols.size(), maxDays);
        dataFetchService.preloadDailyKlines(symbols, maxDays);

        var stCfg = appProperties.getAnalysis().getShortTermRise();
        if (stCfg.isEnabled()) {
            int shortPeriod = stCfg.getPeriod() + appProperties.getConcurrency().getHistoryBufferDays();
            log.info("Preloading {} klines for {} symbols, {} periods", stCfg.getInterval(), symbols.size(), shortPeriod);
            dataFetchService.fetchKlinesByInterval(symbols, stCfg.getInterval(), shortPeriod);
        }
    }

    @Scheduled(cron = "${binance.schedule.symbol-update}")
    public void updateSymbols() {
        log.info("=== Scheduled: Updating USDT futures symbols ===");
        try {
            List<String> symbols = symbolService.fetchAndSaveSymbols().stream()
                    .map(s -> s.getSymbol())
                    .toList();
            cachedSymbols = symbols;
            log.info("Updated {} symbols", symbols.size());
        } catch (Exception e) {
            log.error("Failed to update symbols", e);
        }
    }

    @Scheduled(cron = "${binance.schedule.daily-analysis}")
    public void runDailyAnalysis() {
        log.info("=== Scheduled: Running daily analysis ===");
        List<String> symbols = getSymbols();

        int maxDays = getMaxDailyDays();
        log.info("Preloading daily klines for {} symbols, {} days", symbols.size(), maxDays);
        dataFetchService.preloadDailyKlines(symbols, maxDays);

        for (Analyzer analyzer : analyzers) {
            if (analyzer instanceof ShortTermRiseAnalyzer) continue;
            if (!analyzer.isEnabled()) {
                log.info("Analyzer {} is disabled, skipping", analyzer.getName());
                continue;
            }

            try {
                log.info("Running analyzer: {}", analyzer.getName());
                AnalysisReport report = analyzer.analyze(symbols);
                reportWriter.writeReport(report);
                latestReports.put(report.getAnalysisType(), report);
                log.info("Analyzer {} completed: {} matched", analyzer.getName(), report.getMatchedCount());
            } catch (Exception e) {
                log.error("Analyzer {} failed", analyzer.getName(), e);
            }
        }
    }

    @Scheduled(cron = "${binance.schedule.short-term-analysis}")
    public void runShortTermAnalysis() {
        log.info("=== Scheduled: Running short-term analysis ===");
        List<String> symbols = getSymbols();

        for (Analyzer analyzer : analyzers) {
            if (!(analyzer instanceof ShortTermRiseAnalyzer)) continue;
            if (!analyzer.isEnabled()) {
                log.info("Analyzer {} is disabled, skipping", analyzer.getName());
                continue;
            }

            try {
                log.info("Running analyzer: {}", analyzer.getName());
                AnalysisReport report = analyzer.analyze(symbols);
                reportWriter.writeReport(report);
                latestReports.put(report.getAnalysisType(), report);
                log.info("Analyzer {} completed: {} matched", analyzer.getName(), report.getMatchedCount());
            } catch (Exception e) {
                log.error("Analyzer {} failed", analyzer.getName(), e);
            }
        }
    }

    public boolean isRunning(String name) {
        return runningAnalyzers.contains(name);
    }

    public void runAsync(String name, Analyzer analyzer) {
        asyncExecutor.submit(() -> {
            runningAnalyzers.add(name);
            try {
                log.info("Async run started: {}", name);
                List<String> symbols = getSymbols();
                AnalysisReport report = analyzer.analyze(symbols);
                reportWriter.writeReport(report);
                latestReports.put(report.getAnalysisType(), report);
                log.info("Async run completed: {}, matched: {}", name, report.getMatchedCount());
            } catch (Exception e) {
                log.error("Async run failed: {}", name, e);
            } finally {
                runningAnalyzers.remove(name);
            }
        });
    }

    public Map<String, AnalysisReport> getLatestReports() {
        return latestReports;
    }

    private List<String> getSymbols() {
        if (cachedSymbols.isEmpty()) {
            synchronized (symbolsLock) {
                if (cachedSymbols.isEmpty()) {
                    cachedSymbols = symbolService.getSymbolNames();
                }
            }
        }
        return cachedSymbols;
    }

    private int getMaxDailyDays() {
        AppProperties.AnalysisConfig ac = appProperties.getAnalysis();
        int maxDays = Math.max(
            Math.max(ac.getConsecutiveRise().getDays(), ac.getRiseThenDrop().getDays()),
            Math.max(
                Math.max(ac.getHighRise().getDays(), ac.getDropThenRise().getDays()),
                Math.max(ac.getSlowRise().getDays(),
                    Math.max(ac.getPriceDropOiRise().getDays(),
                        Math.max(ac.getLowPriceConsolidation().getDays(),
                            Math.max(ac.getOiConsecutiveRise().getDays(),
                        Math.max(ac.getFirstYinDay().getDays(), ac.getBullishAccumulation().getDays())))))
            )
        );
        return maxDays + appProperties.getConcurrency().getHistoryBufferDays();
    }
}
