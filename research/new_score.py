#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""新"反转质量评分": 基于回测有效因子。验证能否拉出胜率梯度。"""
import importlib.util
spec=importlib.util.spec_from_file_location("bt","research/backtest_reversal_long.py")
bt=importlib.util.module_from_spec(spec); spec.loader.exec_module(bt)
CFG=bt.CFG
data=bt.load()

def quality_score(decline_days, decline_pct, rev_pct, lower_wick, close_pos):
    """0-100. 全部基于2739笔回测的有效方向。"""
    s=0
    # 连跌天数(0-25): 5天最佳, 4/6次之, 7+差
    s += {4:12, 5:25, 6:15}.get(decline_days, 0 if decline_days>=7 else 0)
    # 累计跌幅(0-20): 6-7%最佳, 越大越差
    if decline_pct<6: s+=15
    elif decline_pct<7: s+=20
    elif decline_pct<8: s+=15
    elif decline_pct<9: s+=10
    else: s+=3
    # 反转日涨幅(0-20): 越温和越好
    if rev_pct<1.5: s+=20
    elif rev_pct<2: s+=16
    elif rev_pct<2.5: s+=10
    elif rev_pct<3: s+=5
    else: s+=0
    # 下影占比(0-20): 越短越好
    if lower_wick<0.1: s+=20
    elif lower_wick<0.2: s+=12
    elif lower_wick<0.3: s+=4
    else: s+=0
    # 收盘位置(0-15): 下半区最好
    if close_pos<0.5: s+=15
    elif close_pos<0.7: s+=8
    else: s+=0
    return min(100,s)

trades=[]
for sym,k in data.items():
    if len(k)<CFG['declineMinDays']+2: continue
    n=len(k); dmin=CFG['declineMinDays']
    for rev in range(dmin+1,n-1):
        de=rev-1; ds=de
        while ds-1>=0 and k[ds-1]['c']>k[ds]['c']: ds-=1
        dd=de-ds+1
        if dd<dmin: continue
        ref=k[ds-1]['c'] if ds-1>=0 else None
        if not ref or ref<=0: continue
        de_price=k[de]['c']; dpct=abs((de_price-ref)/ref*100)
        if dpct<CFG['declineMinPct'] or dpct>CFG['declineMaxPct']: continue
        rd=k[rev]; rpct=(rd['c']-de_price)/de_price*100
        if rpct<CFG['reversalMinPct'] or rpct>CFG['reversalMaxPct']: continue
        if rd['c']<=de_price: continue
        rng=rd['h']-rd['l']; bodyLow=min(rd['o'],rd['c'])
        lw=(bodyLow-rd['l'])/rng if rng>0 else 0
        cp=(rd['c']-rd['l'])/rng if rng>0 else 0
        sc=quality_score(dd,dpct,rpct,lw,cp)
        r=bt.simulate(k,dict(entry_idx=rev+1),CFG)
        if r is None: continue
        trades.append(dict(ret=r[0],win=1 if r[0]>0 else 0,score=sc))

N=len(trades); base=sum(t['win'] for t in trades)/N*100
print(f"\n基准 {N}笔 胜率{base:.1f}%\n")
print(f"{'新质量分档':<14}{'交易数':>7}{'胜率':>9}{'均益%':>9}{'累计%':>9}{'盈亏比':>8}")
print('-'*60)
for thr in [0,40,55,65,75,85]:
    ts=[t for t in trades if t['score']>=thr]
    if not ts: continue
    wr=sum(t['win'] for t in ts)/len(ts)*100
    avg=sum(t['ret'] for t in ts)/len(ts)
    cum=sum(t['ret'] for t in ts)
    gw=sum(t['ret'] for t in ts if t['ret']>0); gl=abs(sum(t['ret'] for t in ts if t['ret']<0))
    pf=gw/gl if gl>0 else 999
    print(f"  score>={thr:<6}{len(ts):>7}{wr:>8.1f}%{avg:>9.2f}{cum:>9.0f}{pf:>8.2f}")
