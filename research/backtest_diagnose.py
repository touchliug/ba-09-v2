#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断: 三个子项各自对胜率的影响 + 入场判据本身的参数敏感性"""
import pymysql, collections
import importlib.util
spec=importlib.util.spec_from_file_location("bt","research/backtest_reversal_long.py")
bt=importlib.util.module_from_spec(spec); spec.loader.exec_module(bt)
CFG=bt.CFG

data=bt.load()
# 重新跑信号, 但保留三个子项分值
trades=[]
for sym,k in data.items():
    if len(k)<CFG['declineMinDays']+2: continue
    n=len(k); dmin=CFG['declineMinDays']
    for rev in range(dmin+1,n-1):
        de=rev-1; ds=de
        while ds-1>=0 and k[ds-1]['c']>k[ds]['c']: ds-=1
        if de-ds+1<dmin: continue
        ref=k[ds-1]['c'] if ds-1>=0 else None
        if not ref or ref<=0: continue
        de_price=k[de]['c']; decline_pct=abs((de_price-ref)/ref*100)
        if decline_pct<CFG['declineMinPct'] or decline_pct>CFG['declineMaxPct']: continue
        rd=k[rev]; rev_pct=(rd['c']-de_price)/de_price*100
        if rev_pct<CFG['reversalMinPct'] or rev_pct>CFG['reversalMaxPct']: continue
        if rd['c']<=de_price: continue
        dvol=[k[d]['qv'] for d in range(ds,de+1)]; avgvol=sum(dvol)/len(dvol) if dvol else 0
        vr=rd['qv']/avgvol if avgvol>0 else 0
        score,vs,ws,rsc=bt.exhaustion_score(k,ds,de,rev,rev_pct,vr,CFG)
        r=bt.simulate(k,dict(entry_idx=rev+1),CFG)
        if r is None: continue
        ret,hold,reason=r
        trades.append(dict(ret=ret,vs=vs,ws=ws,rsc=rsc,vr=vr,
                           decline_pct=decline_pct,rev_pct=rev_pct,decline_days=de-ds+1))

def stat(ts,label):
    if not ts: print(f"{label:<22}{'0':>7}"); return
    wr=sum(1 for t in ts if t['ret']>0)/len(ts)*100
    avg=sum(t['ret'] for t in ts)/len(ts)
    print(f"{label:<22}{len(ts):>7}{wr:>8.1f}%{avg:>10.2f}")

print(f"\n{'维度':<22}{'交易数':>7}{'胜率':>9}{'均益%':>10}")
print("-"*50)
print("[放量分 volScore]")
for lo,hi in [(0,10),(10,20),(20,30),(30,40),(40,41)]:
    stat([t for t in trades if lo<=t['vs']<hi], f"  vol {lo}-{hi}")
print("[长下影分 wickScore]")
for lo,hi in [(0,10),(10,20),(20,30),(30,31)]:
    stat([t for t in trades if lo<=t['ws']<hi], f"  wick {lo}-{hi}")
print("[止跌翻红分 recoverScore]")
for lo,hi in [(0,1),(15,16),(20,26),(26,31)]:
    stat([t for t in trades if lo<=t['rsc']<hi], f"  recover {lo}-{hi}")
print("[反转日量比 vol_ratio 直接分档]")
for lo,hi in [(0,1),(1,2),(2,3),(3,5),(5,99)]:
    stat([t for t in trades if lo<=t['vr']<hi], f"  ratio {lo}-{hi}x")
print("[连跌幅度]")
for lo,hi in [(5,7),(7,9),(9,12)]:
    stat([t for t in trades if lo<=t['decline_pct']<hi], f"  跌幅 {lo}-{hi}%")
print("[反转日涨幅]")
for lo,hi in [(1,2),(2,3),(3,4)]:
    stat([t for t in trades if lo<=t['rev_pct']<hi], f"  反转 {lo}-{hi}%")
print("[连跌天数]")
for d in [4,5,6,7]:
    stat([t for t in trades if t['decline_days']==d], f"  连跌{d}天")
stat([t for t in trades if t['decline_days']>=8], "  连跌>=8天")
