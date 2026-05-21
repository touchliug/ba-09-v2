package com.ba.analyzer.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "binance")
public class AppProperties {

    @NotBlank
    private String baseUrl = "https://fapi.binance.com";
    private ProxyConfig proxy = new ProxyConfig();
    private ConcurrencyConfig concurrency = new ConcurrencyConfig();
    private CacheConfig cache = new CacheConfig();
    private SymbolConfig symbol = new SymbolConfig();
    private AnalysisConfig analysis = new AnalysisConfig();
    private ScheduleConfig schedule = new ScheduleConfig();
    private ReportConfig report = new ReportConfig();

    @Data
    public static class ProxyConfig {
        private boolean enabled = true;
        private String host = "127.0.0.1";
        private int port = 7890;
    }

    @Data
    public static class ConcurrencyConfig {
        @Min(1) @Max(50)
        private int maxRequests = 5;
        @Min(0)
        private long requestIntervalMs = 200;
        @Min(1)
        private int historyBufferDays = 30;
        @Min(10)
        private int taskTimeoutSeconds = 120;
    }

    @Data
    public static class CacheConfig {
        @Min(1)
        private int ttlMinutes = 5;
        @Min(10)
        private int maxSize = 500;
    }

    @Data
    public static class SymbolConfig {
        private String filePath = "./data/symbols.json";
    }

    @Data
    public static class AnalysisConfig {
        private ConsecutiveRiseConfig consecutiveRise = new ConsecutiveRiseConfig();
        private RiseThenDropConfig riseThenDrop = new RiseThenDropConfig();
        private HighRiseConfig highRise = new HighRiseConfig();
        private DropThenRiseConfig dropThenRise = new DropThenRiseConfig();
        private SlowRiseConfig slowRise = new SlowRiseConfig();
        private PriceDropOiRiseConfig priceDropOiRise = new PriceDropOiRiseConfig();
        private LowPriceConsolidationConfig lowPriceConsolidation = new LowPriceConsolidationConfig();
        private ShortTermRiseConfig shortTermRise = new ShortTermRiseConfig();
        private BullishAccumulationConfig bullishAccumulation = new BullishAccumulationConfig();
        private FirstYinDayConfig firstYinDay = new FirstYinDayConfig();
        private OiConsecutiveRiseConfig oiConsecutiveRise = new OiConsecutiveRiseConfig();
        private NMinMaxConfig nMinMax = new NMinMaxConfig();
    }

    @Data
    public static class ConsecutiveRiseConfig {
        private boolean enabled = true;
        private int days = 3;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class RiseThenDropConfig {
        private boolean enabled = true;
        private int days = 3;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class HighRiseConfig {
        private boolean enabled = true;
        private int days = 7;
        private double thresholdPercent = 50;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class DropThenRiseConfig {
        private boolean enabled = true;
        private int days = 5;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class SlowRiseConfig {
        private boolean enabled = true;
        private int days = 7;
        private double maxDailyChangePercent = 3;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class PriceDropOiRiseConfig {
        private boolean enabled = true;
        private int days = 5;
        private double maxPriceRisePercent = 10;
        private double minOiRisePercent = 20;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class LowPriceConsolidationConfig {
        private boolean enabled = true;
        private int days = 7;
        private double maxPriceChangePercent = 10;
        private double lowPricePercentile = 30;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class ShortTermRiseConfig {
        private boolean enabled = true;
        private String interval = "5m";
        private int period = 6;
        private double thresholdPercent = 10;
        private double volumeSurgeRatio = 1.0;
        private int minScore = 70;
        private double launchBodyRatio = 0.6;
        private double launchClosePos = 0.8;
        private double launchVolRatio = 3.0;
        private double takerBuyRatio = 0.55;
    }

    @Data
    public static class BullishAccumulationConfig {
        private boolean enabled = false;
        private int days = 7;
        private int minScore = 65;
        private double consolidationMaxAmplitude = 10;
        private double quietVolumeMaxRatio = 0.8;
        private double wickBodyRatio = 1.5;
        private double oiBeforePriceMinGrowth = 5;
        private double oiBeforePriceMaxPriceRise = 5;
        private double silentBuyerRatio = 1.3;
        private double silentBuyerMaxRatioCap = 10.0;
        private double priceChangeMin = -3.0;
        private double priceChangeMax = 8.0;
    }

    @Data
    public static class FirstYinDayConfig {
        private boolean enabled = false;
        private int days = 3;
        private double minTotalRisePercent = 15;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class OiConsecutiveRiseConfig {
        private boolean enabled = false;
        private int days = 5;
        private double maxPriceRisePercent = 7;
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class NMinMaxConfig {
        private boolean enabled = false;
        private int days = 7;
    }

    @Data
    public static class ScheduleConfig {
        private String symbolUpdate = "0 0 6 * * ?";
        private String dailyAnalysis = "0 5 8 * * ?";
        private String shortTermAnalysis = "0 */10 * * * ?";
    }

    @Data
    public static class ReportConfig {
        private String dir = "./reports";
    }
}
