package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 反转做多分析器 (基于历史回测优化)
 *
 * 策略: 连跌N天 → 小反转 → 第1天开盘做多
 * 回测结果(2026/01-06, 527币种, 365笔):
 *   胜率65.8%, 均值+1.46%, 盈亏比3.52
 */
@Slf4j
@Component
public class ReversalLongAnalyzer extends AbstractKlineAnalyzer {

    public ReversalLongAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "反转做多";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getReversalLong();
        return String.format("连跌>=%d天(%.0f-%.0f%%)+反转%.0f-%.0f%%, 第1天开盘做多, 止盈%.0f%%止损%.0f%%",
                cfg.getDeclineMinDays(), cfg.getDeclineMinPct(), cfg.getDeclineMaxPct(),
                cfg.getReversalMinPct(), cfg.getReversalMaxPct(),
                cfg.getTakeProfitPct(), cfg.getStopLossPct());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getReversalLong().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getReversalLong();
        return createParams(
                "declineMinDays", cfg.getDeclineMinDays(),
                "declineMinPct", cfg.getDeclineMinPct(),
                "declineMaxPct", cfg.getDeclineMaxPct(),
                "reversalMinPct", cfg.getReversalMinPct(),
                "reversalMaxPct", cfg.getReversalMaxPct(),
                "takeProfitPct", cfg.getTakeProfitPct(),
                "stopLossPct", cfg.getStopLossPct(),
                "volumeConfirmRatio", cfg.getVolumeConfirmRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getReversalLong();
        return createParams(
                "declineMinDays", getIntParam(requestParams, "declineMinDays", cfg.getDeclineMinDays()),
                "declineMinPct", getDoubleParam(requestParams, "declineMinPct", cfg.getDeclineMinPct()),
                "declineMaxPct", getDoubleParam(requestParams, "declineMaxPct", cfg.getDeclineMaxPct()),
                "reversalMinPct", getDoubleParam(requestParams, "reversalMinPct", cfg.getReversalMinPct()),
                "reversalMaxPct", getDoubleParam(requestParams, "reversalMaxPct", cfg.getReversalMaxPct()),
                "takeProfitPct", getDoubleParam(requestParams, "takeProfitPct", cfg.getTakeProfitPct()),
                "stopLossPct", getDoubleParam(requestParams, "stopLossPct", cfg.getStopLossPct()),
                "volumeConfirmRatio", getDoubleParam(requestParams, "volumeConfirmRatio", cfg.getVolumeConfirmRatio()));
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        // Sort: 跌势衰竭信号优先, then by reversal strength
        matched.sort(Comparator
                .comparing((AnalysisReport.CoinAnalysis c) -> {
                    String d = c.getDetail();
                    return (d != null && d.contains("跌势衰竭")) ? 0 : 1;
                })
                .thenComparingDouble(c -> {
                    String d = c.getDetail();
                    if (d == null) return 0;
                    try {
                        int idx = d.indexOf("反转+");
                        if (idx >= 0) {
                            int end = d.indexOf('%', idx);
                            return -Double.parseDouble(d.substring(idx + 3, end));
                        }
                    } catch (Exception ignored) {}
                    return 0;
                }));
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("连跌>=%d天(%.0f-%.0f%%)+反转%.0f-%.0f%%, 第1天开盘做多, 止盈%.0f%%止损%.0f%%",
                (int) params.get("declineMinDays"),
                (double) params.get("declineMinPct"), (double) params.get("declineMaxPct"),
                (double) params.get("reversalMinPct"), (double) params.get("reversalMaxPct"),
                (double) params.get("takeProfitPct"), (double) params.get("stopLossPct"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int declineMinDays = (int) params.get("declineMinDays");
        double declineMinPct = (double) params.get("declineMinPct");
        double declineMaxPct = (double) params.get("declineMaxPct");
        double reversalMinPct = (double) params.get("reversalMinPct");
        double reversalMaxPct = (double) params.get("reversalMaxPct");
        double takeProfitPct = (double) params.get("takeProfitPct");
        double stopLossPct = (double) params.get("stopLossPct");
        double volumeConfirmRatio = (double) params.get("volumeConfirmRatio");

        int minNeeded = declineMinDays + 2; // decline days + reversal + entry
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < minNeeded) continue;

            // Don't exclude today's kline — we only need its open price for entry.
            // The skip/reversal/decline days all have complete close prices.
            List<KlineData> complete = new ArrayList<>(klines);
            if (complete.size() < minNeeded) continue;

            // Only check today as the entry day (entryIdx = newest kline).
            // We only need today's open price; skip/reversal/decline all use prior complete days.
            int entryIdx = complete.size() - 1;
                int reversalIdx = entryIdx - 1;
                int declineEndIdx = entryIdx - 2;
                int declineStartIdx = declineEndIdx - declineMinDays + 1;
                if (declineStartIdx < 0) continue;

                // Verify decline: consecutive down days
                boolean allDown = true;
                double declineStartPrice = complete.get(declineStartIdx - 1 >= 0 ? declineStartIdx - 1 : declineStartIdx).getClosePrice();
                // The day before decline starts is the reference price
                int refIdx = declineStartIdx - 1;
                if (refIdx < 0) continue;
                double refPrice = complete.get(refIdx).getClosePrice();
                if (refPrice <= 0) continue;

                for (int d = declineStartIdx; d <= declineEndIdx; d++) {
                    if (complete.get(d).getClosePrice() >= complete.get(d - 1).getClosePrice()) {
                        allDown = false;
                        break;
                    }
                }
                if (!allDown) continue;

                // Check decline is at least declineMinDays (already verified by loop range)
                int actualDeclineDays = declineEndIdx - declineStartIdx + 1;
                // Also extend: there might be more down days before declineStartIdx
                int extStart = declineStartIdx;
                while (extStart > 0 && complete.get(extStart - 1).getClosePrice() < complete.get(extStart).getClosePrice()) {
                    extStart--;
                    actualDeclineDays++;
                }
                if (actualDeclineDays < declineMinDays) continue;

                // Recalculate reference price from the extended start
                refIdx = extStart - 1;
                if (refIdx < 0) continue;
                refPrice = complete.get(refIdx).getClosePrice();

                double declineEndPrice = complete.get(declineEndIdx).getClosePrice();
                double declinePct = Math.abs((declineEndPrice - refPrice) / refPrice * 100);
                if (declinePct < declineMinPct || declinePct > declineMaxPct) continue;

                // Verify reversal day
                KlineData reversalDay = complete.get(reversalIdx);
                double reversalPct = (reversalDay.getClosePrice() - declineEndPrice) / declineEndPrice * 100;
                if (reversalPct < reversalMinPct || reversalPct > reversalMaxPct) continue;
                if (reversalDay.getClosePrice() <= complete.get(declineEndIdx).getClosePrice()) continue;

                // Volume confirmation: reversal day volume vs decline period average
                double declineVolSum = 0;
                int declineVolCount = 0;
                for (int d = extStart; d <= declineEndIdx; d++) {
                    declineVolSum += complete.get(d).getQuoteVolume();
                    declineVolCount++;
                }
                double avgDeclineVol = declineVolCount > 0 ? declineVolSum / declineVolCount : 0;
                double revVolRatio = avgDeclineVol > 0 ? reversalDay.getQuoteVolume() / avgDeclineVol : 0;
                if (volumeConfirmRatio > 1.0 && revVolRatio < volumeConfirmRatio) continue;

                // Exhaustion detection: is the decline losing momentum?
                // Compare last 2 down days vs first 2 down days
                int totalDecline = declineEndIdx - extStart + 1;
                int halfPt = extStart + Math.max(1, totalDecline / 3);
                double firstPartSum = 0, lastPartSum = 0;
                int firstCnt = 0, lastCnt = 0;
                double firstVolSum = 0, lastVolSum = 0;
                for (int d = extStart; d <= declineEndIdx; d++) {
                    double dayChg = Math.abs((complete.get(d).getClosePrice() - complete.get(d-1).getClosePrice())
                            / complete.get(d-1).getClosePrice() * 100);
                    if (d < halfPt) {
                        firstPartSum += dayChg; firstCnt++;
                        firstVolSum += complete.get(d).getQuoteVolume();
                    } else {
                        lastPartSum += dayChg; lastCnt++;
                        lastVolSum += complete.get(d).getQuoteVolume();
                    }
                }
                double firstAvgChg = firstCnt > 0 ? firstPartSum / firstCnt : 0;
                double lastAvgChg = lastCnt > 0 ? lastPartSum / lastCnt : 0;
                double firstAvgVol = firstCnt > 0 ? firstVolSum / firstCnt : 0;
                double lastAvgVol = lastCnt > 0 ? lastVolSum / lastCnt : 0;
                // Exhaustion: later declines are smaller AND volume is lower
                boolean isExhausting = totalDecline >= 4
                        && lastAvgChg < firstAvgChg * 0.7
                        && lastAvgVol < firstAvgVol * 0.8;
                // Last candle: small body (doji/indecision) = exhaustion signal
                KlineData lastDecline = complete.get(declineEndIdx);
                double lastBody = Math.abs(lastDecline.getClosePrice() - lastDecline.getOpenPrice());
                double lastRange = lastDecline.getHighPrice() - lastDecline.getLowPrice();
                boolean lastCandleSmall = lastRange > 0 && (lastBody / lastRange) < 0.3;
                if (isExhausting || lastCandleSmall) {
                    // Boost detail with exhaustion marker
                }
                String exhaustMark = (isExhausting || lastCandleSmall) ? " 跌势衰竭" : "";

                // Signal found!
                KlineData entryDay = complete.get(entryIdx);
                double entryPrice = entryDay.getOpenPrice();
                double targetPrice = entryPrice * (1 + takeProfitPct / 100);
                double stopPrice = entryPrice * (1 - stopLossPct / 100);
                double riskReward = takeProfitPct / stopLossPct;

                // Format entry date
                java.time.LocalDateTime entryDate = java.time.LocalDateTime.ofEpochSecond(
                        entryDay.getOpenTime() / 1000, 0, java.time.ZoneOffset.ofHours(8));
                String dateStr = entryDate.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"));

                matched.add(AnalysisReport.CoinAnalysis.builder()
                        .symbol(symbol)
                        .currentPrice(entryPrice)
                        .changePercent(reversalPct)
                        .detail(String.format(
                                "[%s] 连跌%d天累跌%.1f%% 反转+%.1f%%(量比%.1fx)%s "
                                        + "| 入场%.4f 止盈%.4f(+%.0f%%) 止损%.4f(-%.0f%%) 盈亏比%.1f",
                                dateStr, actualDeclineDays, declinePct, reversalPct, revVolRatio, exhaustMark,
                                entryPrice, targetPrice, takeProfitPct, stopPrice, stopLossPct, riskReward))
                        .build());
        }

        return matched;
    }
}
