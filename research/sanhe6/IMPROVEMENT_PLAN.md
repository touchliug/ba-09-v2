# ba-09-v2 改进规划(一步到位版)

> 来源:基于 sanhe6.com 反推 + 你数据库真实数据验证。核心洞见——盈利单的共同形态是
> **「下跌末端 + 开单日放量 + 长下影 + 止跌翻红」**,且 sanhe6 用 5m/1h/6h/24h/30d 多窗口 OI 抓"合约点火"、
> 用 24h 涨幅门做"回踩/追高保护"、用 6 维加权评分做全市场横向排序。

## 范围确认
- **衰竭反转增强**:只加在 `ReversalLongAnalyzer`(你的要求)。
- **多窗口特征引擎 / 回踩保护 / 综合评分层 / 日内OI改造**:按我的设计,全做(一步到位)。

---

## Phase 0 — 基础设施:日内 OI 多周期存储

**目标**:从只存日线 OI,升级到能存任意周期(5m/1h/1d),为"6小时级点火"提供数据。

1. **`JdbcDataStore`**:给 `open_interest` 表加 `period VARCHAR(10)` 列,主键改为 `(symbol, period, timestamp)`。
   - 新建表用 `CREATE TABLE IF NOT EXISTS`;对已存在的旧表,`init()` 里加一段幂等迁移
     (检测无 period 列则 `ALTER TABLE ... ADD COLUMN period ... DEFAULT '1d'` 并重建主键)。
   - `saveOpenInterest` / `getOpenInterestHistory` 增加 `period` 参数;旧的无 period 方法保留并默认 `"1d"`,避免破坏现有调用。
2. **`BinanceClient.getOpenInterestHistory`** 已支持 period 参数,无需改。
3. **`DataFetchService`**:新增 `fetchOiHistoryByPeriod(symbols, period, limit)`,逻辑同现有但带 period。
   - 拉两条序列:`5m`(覆盖约 41h,够算 5m/1h/6h/24h 窗口)+ `1d`(算 30d 窗口)。
4. **`AnalysisScheduler`**:在 `short-term-analysis`(每5分钟)里同步 5m OI;日线 OI 维持原节奏。
   - 注意限流:531 symbol × 2 周期,沿用现有 `executeConcurrent` 信号量,评估耗时,必要时调 `request-interval-ms`。

**验证**:启动后查 MySQL,确认 `open_interest` 有 `period='5m'` 与 `'1d'` 两类数据,且 5m 数据新鲜。

---

## Phase 1 — 多窗口特征引擎 `MarketFeatureService`(新建)

**目标**:复刻 sanhe6 的 `oiWindows` + 多窗口价格/量能,产出一个可被任何分析器复用的特征对象。

- 新建 `model/MarketFeature.java`:
  ```
  oiChange:    m5, h1, h6, h24, d30      (持仓量各窗口变化率%)
  priceChange: h6, h24                    (价格,h6 由 5m K线推导)
  volChange:   h6Vs6h(近6h/前6h), vs30dAvg
  fundingRate, quoteVolume24h
  ```
- 新建 `service/MarketFeatureService.java`:输入 symbol + 5m K线 + 5m/1d OI,输出 `MarketFeature`。
  - 价格 h6 = 最近 72 根 5m K线的首尾;OI 各窗口 = 当前值 vs N 根前。
- 派生信号判据(对应 sanhe6 的 oiSignal/volumeSignal/fundingSignal):
  - **合约点火** = `d30 低位(<0 或低分位)` AND `h6 > 阈值` AND `m5 不塌(>-X%)`
  - **量能接力** = `volChange.h6Vs6h > 阈值`
  - **费率健康** = funding 接近中性区间

**验证**:对 INIT/MOVE 等已知标的算特征,与 `research/sanhe6/dash_public.json` 里的真实值对照校准阈值。

---

## Phase 2 — 衰竭反转增强(仅 `ReversalLongAnalyzer`)

**目标**:把验证过的"放量+长下影+止跌翻红"从现有的弱标记(`跌势衰竭`字符串)升级为**核心评分判据**,并写入 `CoinAnalysis.score`。

现状:已有 `isExhausting`(后段跌幅+量能衰减)和 `lastCandleSmall`(小实体)两个弱信号,只拼进 detail 字符串。

改进——新增衰竭评分(0-100),由三个验证过的子项加权:
1. **放量**:开单日(反转日)`quoteVolume / 跌势均量`。验证数据里盈利单多为 3~13x;给量比分档计分。
2. **长下影**:反转日 `下影长度 / 振幅` 占比。OPN(8.3%)、HEI(10.2%)等盈利单显著;计分。
3. **止跌翻红**:反转日 `close > open` 且收复跌势末日跌幅的比例。

- 评分写入 `CoinAnalysis.score`,detail 增加 `衰竭分 XX(放量a/下影b/翻红c)`。
- `sortResults` 改为按衰竭分降序(替换现有脆弱的字符串解析排序)。
- config `ReversalLongConfig` 增参数:`exhaustionMinScore`、`volSurgeStrong`(默认3.0)、`lowerWickMinRatio`。
- **保持回测纪律**:不改变其入场/止盈止损语义,只增强筛选与排序。

**验证**:写单测覆盖三个子项;用 `research/sanhe6/live_trades.json` 的 9 个标的(数据库已有日线)做离线核对,确认 6 个抄底盈利单衰竭分高、唯一亏损的追高单 ALLO 分低。

---

## Phase 3 — 回踩/追高保护 `PullbackGate`(新建,共享)

**目标**:复刻 sanhe6 的 `WAIT_PULLBACK` 门——涨太多不追第一波,等回踩 OI 不塌再二次确认。

- 新建 `analysis/support/PullbackGate.java`(无状态工具):
  - 输入 `MarketFeature`,输出 `EntryStage` 枚举:`WATCH / SETUP / WAIT_PULLBACK / ENTRY / AVOID`。
  - 规则:`priceChange.h24 > 阈值(默认15%)` → `WAIT_PULLBACK`,理由"24h涨X%,仅允许回踩后二次确认"。
  - 二次确认:价格自高点回落 Y% 且 `oiChange.h6 不塌` 且量能仍在 → 升级 `ENTRY`。
- 由 Phase 4 综合层统一调用(也可被短期急涨类分析器复用,本轮先接综合层)。

**验证**:单测构造"暴涨后"与"回踩企稳"两种特征,断言 stage 流转正确。

---

## Phase 4 — 综合评分层 `CompositeScoreAnalyzer`(新建)

**目标**:解决"14 个分析器各出各报告、无法横向比较"。复刻 sanhe6 的 6 维加权 + 分层漏斗,产出全市场 0-100 排序。

- 新建 `analysis/CompositeScoreAnalyzer.java`(实现 `Analyzer`,名称"综合评分")。
- 复用 `MarketFeatureService` 的特征,按 sanhe6 权重打分(你有 4 维,社媒/多交易所暂置 0 或留扩展位):
  | 维度 | 满分 | 来源 |
  |---|---|---|
  | OI | 25 | MarketFeature.oiChange 多窗口 |
  | price | 20 | priceChange + 位置 |
  | funding | 15 | fundingRate 中性度 |
  | volume | 15 | volChange |
  | (social) | 15 | 预留=0 |
  | (venue) | 10 | 预留=0 |
- 叠加 `PullbackGate` 定 stage,叠加各币种的"衰竭分/点火/量能接力"标签。
- 输出:按综合分降序的 `AnalysisReport`,detail 含 stage + 分项拆解。
- **扩展 `AnalysisReport.CoinAnalysis`**:增加 `stage`(String)、`scoreBreakdown`(Map)、`side`(String) 字段,
  保持向后兼容(其余分析器不填即 null)。
- config 增 `CompositeScoreConfig`(权重、各阈值、minScore);`AnalysisConfig` 注册;`application.yml` 加配置块,默认 `enabled: true`。
- `AnalysisController.getParamSpec` 增"综合评分"参数说明;`AnalysisScheduler.getMaxDailyDays` 纳入其 days。

**验证**:跑一次,确认全市场被打分排序;抽查高分标的特征合理。

---

## 落地顺序与风险

1. **Phase 0 → 1** 是地基,必须先做且先验证(动了 DB schema)。
2. **Phase 2** 独立,风险最低,可最先出成果(纯日线,不依赖 OI 改造)。
3. **Phase 3 → 4** 依赖 Phase 1 的特征引擎。
4. 全程沿用现有约定:`@Component` 自动注入 `List<Analyzer>`、Mockito 单测不依赖外部、报告写 `reports/`。

**主要风险**:
- **DB 迁移**:`open_interest` 主键变更需幂等迁移,旧数据默认 `period='1d'`。先在你确认后再动 schema。
- **API 限流**:新增 5m OI 拉取使 OI 请求量翻倍,需实测 531 symbol 的耗时,可能要调并发参数或只对候选池拉 5m OI。
- **数据保真**:`dash_public.json` 的 `sourceFetchedAt` 是 4/21,校准阈值时注意时间错位,以你数据库真实 K线为准。

## 验证总策略
每个 Phase 完成后 `mvn test` + 针对性单测;Phase 2/4 用 `research/sanhe6/` 已抓的真实数据做离线对照校准,不靠拍脑袋定阈值。
