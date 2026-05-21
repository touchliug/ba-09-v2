package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BullishAccumulationAnalyzer extends AbstractKlineAnalyzer {

    public BullishAccumulationAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "底部吸筹";
    }

    @Override
    public String getDescription() {
        int minScore = appProperties.getAnalysis().getBullishAccumulation().getMinScore();
        return String.format("横盘缩量+下影承接+仓位先动信号，蓄力评分≥%d的币", minScore);
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getBullishAccumulation().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getBullishAccumulation();
        return createParams("days", cfg.getDays(), "minScore", cfg.getMinScore(),
                "consolidationMaxAmplitude", cfg.getConsolidationMaxAmplitude(),
                "quietVolumeMaxRatio", cfg.getQuietVolumeMaxRatio(),
                "wickBodyRatio", cfg.getWickBodyRatio(),
                "oiBeforePriceMinGrowth", cfg.getOiBeforePriceMinGrowth(),
                "oiBeforePriceMaxPriceRise", cfg.getOiBeforePriceMaxPriceRise(),
                "silentBuyerRatio", cfg.getSilentBuyerRatio(),
                "silentBuyerMaxRatioCap", cfg.getSilentBuyerMaxRatioCap(),
                "priceChangeMin", cfg.getPriceChangeMin(),
                "priceChangeMax", cfg.getPriceChangeMax());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getBullishAccumulation();
        return createParams("days", getIntParam(requestParams, "days", cfg.getDays()),
                "minScore", getIntParam(requestParams, "minScore", cfg.getMinScore()),
                "consolidationMaxAmplitude", getDoubleParam(requestParams, "consolidationMaxAmplitude", cfg.getConsolidationMaxAmplitude()),
                "quietVolumeMaxRatio", getDoubleParam(requestParams, "quietVolumeMaxRatio", cfg.getQuietVolumeMaxRatio()),
                "wickBodyRatio", getDoubleParam(requestParams, "wickBodyRatio", cfg.getWickBodyRatio()),
                "oiBeforePriceMinGrowth", getDoubleParam(requestParams, "oiBeforePriceMinGrowth", cfg.getOiBeforePriceMinGrowth()),
                "oiBeforePriceMaxPriceRise", getDoubleParam(requestParams, "oiBeforePriceMaxPriceRise", cfg.getOiBeforePriceMaxPriceRise()),
                "silentBuyerRatio", getDoubleParam(requestParams, "silentBuyerRatio", cfg.getSilentBuyerRatio()),
                "silentBuyerMaxRatioCap", getDoubleParam(requestParams, "silentBuyerMaxRatioCap", cfg.getSilentBuyerMaxRatioCap()),
                "priceChangeMin", getDoubleParam(requestParams, "priceChangeMin", cfg.getPriceChangeMin()),
                "priceChangeMax", getDoubleParam(requestParams, "priceChangeMax", cfg.getPriceChangeMax()));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        List<String> symbols = new ArrayList<>(klineMap.keySet());
        Map<String, List<OpenInterestData>> oiHistoryMap = dataFetchService.fetchOpenInterestHistoryBatch(symbols, days);
        return doAnalyzeWithOi(klineMap, oiHistoryMap, params);
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort(Comparator.comparingInt((AnalysisReport.CoinAnalysis c) ->
                c.getScore() != null ? c.getScore() : 0).reversed());
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("横盘缩量+下影承接+仓位先动信号，蓄力评分≥%d的币", (int) params.get("minScore"));
    }

    private List<AnalysisReport.CoinAnalysis> doAnalyzeWithOi(
            Map<String, List<KlineData>> klineMap,
            Map<String, List<OpenInterestData>> oiHistoryMap,
            Map<String, Object> params) {
        int days = (int) params.get("days");
        int minScore = (int) params.get("minScore");
        double consolidationMaxAmplitude = (double) params.get("consolidationMaxAmplitude");
        double quietVolumeMaxRatio = (double) params.get("quietVolumeMaxRatio");
        double wickBodyRatio = (double) params.get("wickBodyRatio");
        double oiBeforePriceMaxPriceRise = (double) params.get("oiBeforePriceMaxPriceRise");
        double silentBuyerRatioThreshold = (double) params.get("silentBuyerRatio");
        double silentBuyerMaxRatioCap = (double) params.get("silentBuyerMaxRatioCap");
        double priceChangeMin = (double) params.get("priceChangeMin");
        double priceChangeMax = (double) params.get("priceChangeMax");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days) continue;

            List<KlineData> recentKlines = klines.subList(klines.size() - days, klines.size());
            List<KlineData> historicalKlines = klines.subList(0, klines.size() - days);

            double startPrice = recentKlines.get(0).getOpenPrice();
            double endPrice = recentKlines.get(recentKlines.size() - 1).getClosePrice();
            if (startPrice <= 0) continue;
            double priceChange = ((endPrice - startPrice) / startPrice) * 100;
            if (priceChange < priceChangeMin || priceChange > priceChangeMax) continue;

            double maxHigh = recentKlines.stream().mapToDouble(KlineData::getHighPrice).max().orElse(0);
            double minLow = recentKlines.stream().mapToDouble(KlineData::getLowPrice).min().orElse(0);
            double midPrice = (maxHigh + minLow) / 2;
            double amplitude = midPrice > 0 ? ((maxHigh - minLow) / midPrice) * 100 : 0;

            double histAmplitude = calcHistAmplitude(historicalKlines);
            int cScore = calcConsolidationScore(amplitude, consolidationMaxAmplitude, histAmplitude);

            double historicalAvgVol = historicalKlines.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(0);
            double recentAvgVol = recentKlines.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(0);
            double volRatio = historicalAvgVol > 0 ? recentAvgVol / historicalAvgVol : 1.0;
            long burstDays = historicalAvgVol > 0
                    ? recentKlines.stream().filter(k -> k.getQuoteVolume() > historicalAvgVol * 2.0).count() : 0;
            int qScore = calcQuietVolumeScore(volRatio, quietVolumeMaxRatio, burstDays);

            int wickCount = countWicks(recentKlines, wickBodyRatio);
            int wScore = wickCount >= 5 ? 25 : wickCount >= 3 ? 20 : wickCount >= 1 ? 12 : 0;

            List<OpenInterestData> oiHistory = oiHistoryMap.get(symbol);
            double oiChange = 0;
            int oScore = 0;
            if (oiHistory != null && !oiHistory.isEmpty()) {
                double firstOi = oiHistory.get(0).getOpenInterestValue();
                double lastOi = oiHistory.get(oiHistory.size() - 1).getOpenInterestValue();
                if (firstOi > 0) {
                    oiChange = ((lastOi - firstOi) / firstOi) * 100;
                    oScore = oiChange >= 15 ? 25 : oiChange >= 10 ? 20 : oiChange >= 5 ? 15 : oiChange >= 0 ? 8 : 0;
                    if (oScore > 0 && Math.abs(priceChange) < oiBeforePriceMaxPriceRise)
                        oScore = Math.min(25, oScore + 3);
                }
            }

            double silentRatio = calcSilentBuyerRatio(recentKlines, silentBuyerMaxRatioCap);
            int sScore = silentRatio > 1.8 ? 25 : silentRatio >= silentBuyerRatioThreshold ? 20
                    : silentRatio >= 1.0 ? 12 : 0;

            int totalScore = cScore + qScore + wScore + oScore + sScore;
            if (totalScore < minScore) continue;

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(endPrice)
                    .changePercent(priceChange)
                    .score(totalScore)
                    .detail(String.format(
                            "蓄力评分%d C%d Q%d W%d O%d S%d | 振幅%.1f%% 量比%.2f 针%d次 OI增%.1f%% 吃货比%.2f 放量日%d",
                            totalScore, cScore, qScore, wScore, oScore, sScore,
                            amplitude, volRatio, wickCount, oiChange, silentRatio, burstDays))
                    .build());
        }
        return matched;
    }

    private double calcHistAmplitude(List<KlineData> historicalKlines) {
        if (historicalKlines.size() < 15) return 0;
        int histStart = Math.max(0, historicalKlines.size() - 15);
        List<KlineData> olderKlines = historicalKlines.subList(histStart, historicalKlines.size());
        double maxH = olderKlines.stream().mapToDouble(KlineData::getHighPrice).max().orElse(0);
        double minL = olderKlines.stream().mapToDouble(KlineData::getLowPrice).min().orElse(0);
        double mid = (maxH + minL) / 2;
        return mid > 0 ? ((maxH - minL) / mid) * 100 : 0;
    }

    private int calcConsolidationScore(double amplitude, double maxAmplitude, double histAmplitude) {
        int score = amplitude <= 5 ? 25 : amplitude <= 10 ? 18 : amplitude <= maxAmplitude ? 10 : 0;
        if (histAmplitude > 0 && amplitude < histAmplitude * 0.5 && score < 25)
            score = Math.min(25, score + 5);
        return score;
    }

    private int calcQuietVolumeScore(double volRatio, double maxRatio, long burstDays) {
        int score = volRatio < 0.3 ? 25 : volRatio < 0.5 ? 20 : volRatio <= maxRatio ? 15
                : volRatio <= 1.2 ? 8 : 0;
        if (burstDays == 0 && score > 12) score = 12;
        return score;
    }

    private int countWicks(List<KlineData> klines, double wickBodyRatio) {
        int count = 0;
        for (KlineData k : klines) {
            double lowerWick = Math.min(k.getOpenPrice(), k.getClosePrice()) - k.getLowPrice();
            double body = Math.abs(k.getClosePrice() - k.getOpenPrice());
            if (body > 0 && lowerWick > body * wickBodyRatio) count++;
        }
        return count;
    }

    private double calcSilentBuyerRatio(List<KlineData> klines, double maxCap) {
        double upVol = 0, downVol = 0;
        for (KlineData k : klines) {
            if (k.getClosePrice() > k.getOpenPrice()) upVol += k.getQuoteVolume();
            else downVol += k.getQuoteVolume();
        }
        if (downVol > 0) return upVol / downVol;
        return upVol > 0 ? maxCap : 0;
    }
}
