#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
反转做多策略回测 - 精确复刻 ReversalLongAnalyzer 的入场逻辑与衰竭评分。

入场判据(与Java版一致):
  - 连跌 >= declineMinDays 天 (收盘价逐日下降, 向前扩展所有连跌日)
  - 累计跌幅 in [declineMinPct, declineMaxPct]
  - 反转日(连跌后第一天) 涨幅 in [reversalMinPct, reversalMaxPct] 且收盘 > 跌势末日收盘
  - (可选)反转日量比 >= volumeConfirmRatio
  - 入场: 反转日次日开盘价做多
出场: 止盈 +takeProfitPct% / 止损 -stopLossPct%, 逐日按高低价判定, 同根同穿按止损(保守)
质量评分(0-100): 连跌天数(25)+累计跌幅(20)+反转涨幅(20)+下影占比(20)+收盘位置(15),
                 与Java版 ReversalLongAnalyzer.scoreQuality 同公式 (旧的放量+长下影评分经回测证实反向, 已废弃)。
"""
import pymysql, collections

CFG = dict(
    declineMinDays=4, declineMinPct=5.0, declineMaxPct=12.0,
    reversalMinPct=1.0, reversalMaxPct=4.0,
    takeProfitPct=6.0, stopLossPct=4.0,
    volumeConfirmRatio=1.0,
    maxHoldDays=30,   # 止盈止损都没触发时的最长持有(到期按收盘平)
)

def load():
    c=pymysql.connect(host='localhost',port=3306,user='root',password='Kzfx8N78@123456',
                      db='ba',charset='utf8mb4',connect_timeout=5)
    cur=c.cursor()
    cur.execute("SELECT symbol,open_time,open,high,low,close,quote_asset_volume "
                "FROM klines WHERE `interval`='1d' ORDER BY symbol,open_time")
    data=collections.defaultdict(list)
    for sym,ot,o,h,l,cl,qv in cur.fetchall():
        data[sym].append(dict(t=ot,o=float(o),h=float(h),l=float(l),c=float(cl),
                              qv=float(qv) if qv else 0.0))
    c.close()
    return data

def quality_score(decline_days, decline_pct, rev_pct, rd):
    """复刻 Java 版 ReversalLongAnalyzer.scoreQuality。
    rd 为反转日K线 dict(o,h,l,c)。各档位权重由 2739 笔全量回测校准。
    返回 (total, days, drop, rev, wick, pos)。"""
    # 1) 连跌天数 (0-25): 5天最佳, 4/6次之, 7+天接飞刀
    days_s = {5: 25, 6: 15, 4: 12}.get(decline_days, 0)

    # 2) 累计跌幅 (0-20): 6-7%最佳, 越大越差
    if decline_pct < 6:   drop_s = 15
    elif decline_pct < 7: drop_s = 20
    elif decline_pct < 8: drop_s = 15
    elif decline_pct < 9: drop_s = 10
    else:                 drop_s = 3

    # 3) 反转日涨幅 (0-20): 越温和越好 (1-1.5%最佳)
    if rev_pct < 1.5:   rev_s = 20
    elif rev_pct < 2:   rev_s = 16
    elif rev_pct < 2.5: rev_s = 10
    elif rev_pct < 3:   rev_s = 5
    else:               rev_s = 0

    # 反转日形态
    rng = rd['h'] - rd['l']
    body_low = min(rd['o'], rd['c'])

    # 4) 下影占比 (0-20): 越短越好 (长下影是反向信号)
    lw = (body_low - rd['l']) / rng if rng > 0 else 0
    if lw < 0.1:   wick_s = 20
    elif lw < 0.2: wick_s = 12
    elif lw < 0.3: wick_s = 4
    else:          wick_s = 0

    # 5) 收盘位置 (0-15): 收在当日下半区最好 (温和试探优于冲高)
    close_pos = (rd['c'] - rd['l']) / rng if rng > 0 else 0.5
    if close_pos < 0.5:   pos_s = 15
    elif close_pos < 0.7: pos_s = 8
    else:                 pos_s = 0

    total = min(100, days_s + drop_s + rev_s + wick_s + pos_s)
    return total, days_s, drop_s, rev_s, wick_s, pos_s

def find_signals(sym, k, cfg):
    """逐日滚动: 对每个可能的反转日(rev_idx), 检查其前面是否构成连跌, 产出信号。"""
    sigs=[]
    n=len(k)
    dmin=cfg['declineMinDays']
    for rev in range(dmin+1, n-1):   # 反转日, 次日(rev+1)做入场, 故需 rev+1<=n-1
        de=rev-1                       # 跌势末日
        # 向前找连跌: de, de-1, ... 收盘逐日下降
        ds=de
        while ds-1>=0 and k[ds-1]['c']>k[ds]['c']:
            ds-=1
        decline_days=de-ds+1
        if decline_days<dmin: continue
        ref=k[ds-1]['c'] if ds-1>=0 else None
        if not ref or ref<=0: continue
        de_price=k[de]['c']
        decline_pct=abs((de_price-ref)/ref*100)
        if decline_pct<cfg['declineMinPct'] or decline_pct>cfg['declineMaxPct']: continue
        # 反转日
        rd=k[rev]
        rev_pct=(rd['c']-de_price)/de_price*100
        if rev_pct<cfg['reversalMinPct'] or rev_pct>cfg['reversalMaxPct']: continue
        if rd['c']<=de_price: continue
        # 量比
        dvol=[k[d]['qv'] for d in range(ds,de+1)]
        avgvol=sum(dvol)/len(dvol) if dvol else 0
        rev_vol_ratio=rd['qv']/avgvol if avgvol>0 else 0
        if cfg['volumeConfirmRatio']>1.0 and rev_vol_ratio<cfg['volumeConfirmRatio']: continue
        # 质量评分 (与 Java scoreQuality 同公式)
        score,ds_s,dr_s,rv_s,wk_s,ps_s=quality_score(decline_days,decline_pct,rev_pct,rd)
        sigs.append(dict(sym=sym, entry_idx=rev+1, score=score,
                         decline_days=decline_days, decline_pct=decline_pct,
                         rev_pct=rev_pct, vol_ratio=rev_vol_ratio))
    return sigs

def simulate(k, sig, cfg):
    """从 entry_idx 开盘价做多, 逐日判定止盈/止损。"""
    ei=sig['entry_idx']
    entry=k[ei]['o']
    if entry<=0: return None
    tp=entry*(1+cfg['takeProfitPct']/100)
    sl=entry*(1-cfg['stopLossPct']/100)
    for d in range(ei, min(ei+cfg['maxHoldDays'], len(k))):
        hi,lo=k[d]['h'],k[d]['l']
        hit_sl = lo<=sl
        hit_tp = hi>=tp
        if hit_sl and hit_tp:   # 同根同穿, 保守按止损
            return (-cfg['stopLossPct'], d-ei, 'both->SL')
        if hit_sl: return (-cfg['stopLossPct'], d-ei, 'SL')
        if hit_tp: return (cfg['takeProfitPct'], d-ei, 'TP')
    # 到期未触发, 按最后一日收盘平
    last=k[min(ei+cfg['maxHoldDays'],len(k))-1]['c']
    return ((last-entry)/entry*100, cfg['maxHoldDays'], 'TIMEOUT')

def main():
    print("加载日线数据...")
    data=load()
    print(f"共 {len(data)} 个币种\n")
    # 收集所有信号+结果
    trades=[]
    for sym,k in data.items():
        if len(k)<CFG['declineMinDays']+2: continue
        for sig in find_signals(sym,k,CFG):
            r=simulate(k,sig,CFG)
            if r is None: continue
            ret,hold,reason=r
            trades.append(dict(**sig, ret=ret, hold=hold, reason=reason))
    print(f"总信号(交易)数: {len(trades)}\n")
    # 分档对比 (阈值对齐 Java 注释关口: 55/75/85)
    buckets=[(0,'全部 score>=0'),(55,'score>=55'),(65,'score>=65'),(75,'score>=75'),(85,'score>=85')]
    print(f"{'档位':<16}{'交易数':>7}{'胜率':>9}{'平均收益%':>11}{'累计收益%':>11}{'盈亏比':>9}")
    print("-"*70)
    for thr,label in buckets:
        ts=[t for t in trades if t['score']>=thr]
        if not ts:
            print(f"{label:<16}{0:>7}"); continue
        wins=[t for t in ts if t['ret']>0]
        wr=len(wins)/len(ts)*100
        avg=sum(t['ret'] for t in ts)/len(ts)
        cum=sum(t['ret'] for t in ts)
        gross_win=sum(t['ret'] for t in ts if t['ret']>0)
        gross_loss=abs(sum(t['ret'] for t in ts if t['ret']<0))
        pf=gross_win/gross_loss if gross_loss>0 else 999
        print(f"{label:<16}{len(ts):>7}{wr:>8.1f}%{avg:>11.2f}{cum:>11.0f}{pf:>9.2f}")
    # 出场原因分布(全部)
    print("\n出场原因分布(全部信号):")
    rc=collections.Counter(t['reason'] for t in trades)
    for r,c in rc.most_common(): print(f"  {r}: {c} ({c/len(trades)*100:.1f}%)")

if __name__=='__main__':
    main()
