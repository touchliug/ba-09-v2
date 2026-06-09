#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""基于2739笔回测, 系统搜索有效因子, 重新设计反转质量评分。"""
import collections, importlib.util
spec=importlib.util.spec_from_file_location("bt","research/backtest_reversal_long.py")
bt=importlib.util.module_from_spec(spec); spec.loader.exec_module(bt)
CFG=bt.CFG

data=bt.load()

# 收集所有信号的丰富特征
trades=[]
for sym,k in data.items():
    if len(k)<CFG['declineMinDays']+2: continue
    n=len(k); dmin=CFG['declineMinDays']
    for rev in range(dmin+1,n-1):
        de=rev-1; ds=de
        while ds-1>=0 and k[ds-1]['c']>k[ds]['c']: ds-=1
        decline_days=de-ds+1
        if decline_days<dmin: continue
        ref=k[ds-1]['c'] if ds-1>=0 else None
        if not ref or ref<=0: continue
        de_price=k[de]['c']; decline_pct=abs((de_price-ref)/ref*100)
        if decline_pct<CFG['declineMinPct'] or decline_pct>CFG['declineMaxPct']: continue
        rd=k[rev]; rev_pct=(rd['c']-de_price)/de_price*100
        if rev_pct<CFG['reversalMinPct'] or rev_pct>CFG['reversalMaxPct']: continue
        if rd['c']<=de_price: continue
        dvol=[k[d]['qv'] for d in range(ds,de+1)]; avgvol=sum(dvol)/len(dvol) if dvol else 0
        vr=rd['qv']/avgvol if avgvol>0 else 0
        # 额外特征
        rng=rd['h']-rd['l']; bodyLow=min(rd['o'],rd['c']); bodyHigh=max(rd['o'],rd['c'])
        lower_wick=(bodyLow-rd['l'])/rng if rng>0 else 0
        upper_wick=(rd['h']-bodyHigh)/rng if rng>0 else 0
        body=(rd['c']-rd['o'])/rng if rng>0 else 0   # 实体占振幅(正=阳)
        close_pos=(rd['c']-rd['l'])/rng if rng>0 else 0  # 收盘在当日区间位置
        # 反转日是否放量(相对跌势)
        r=bt.simulate(k,dict(entry_idx=rev+1),CFG)
        if r is None: continue
        ret,hold,reason=r
        win=1 if ret>0 else 0
        trades.append(dict(ret=ret,win=win,vr=vr,decline_days=decline_days,
                           decline_pct=decline_pct,rev_pct=rev_pct,
                           lower_wick=lower_wick,upper_wick=upper_wick,body=body,
                           close_pos=close_pos))

N=len(trades)
base_wr=sum(t['win'] for t in trades)/N*100
print(f"\n基准: {N}笔, 胜率{base_wr:.1f}%\n")

def buckets(key, edges, label):
    print(f"[{label}]")
    for i in range(len(edges)-1):
        lo,hi=edges[i],edges[i+1]
        ts=[t for t in trades if lo<=t[key]<hi]
        if not ts: continue
        wr=sum(x['win'] for x in ts)/len(ts)*100
        avg=sum(x['ret'] for x in ts)/len(ts)
        flag=' ***' if wr>=base_wr+5 else (' xxx' if wr<=base_wr-5 else '')
        print(f"  {lo:>5}-{hi:<5} n={len(ts):>4} 胜率{wr:>5.1f}% 均益{avg:>6.2f}{flag}")

buckets('decline_days',[4,5,6,7,8,99],'连跌天数')
buckets('decline_pct',[5,6,7,8,9,10,12],'累计跌幅%')
buckets('rev_pct',[1,1.5,2,2.5,3,4],'反转日涨幅%')
buckets('lower_wick',[0,0.1,0.2,0.3,0.5,1.01],'下影占比')
buckets('upper_wick',[0,0.1,0.2,0.3,0.5,1.01],'上影占比')
buckets('body',[-1,0.1,0.3,0.5,0.7,1.01],'实体占比(阳)')
buckets('close_pos',[0,0.3,0.5,0.7,0.9,1.01],'收盘位置')
buckets('vr',[0,0.5,0.8,1.0,1.5,2,99],'反转日量比')
