#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""三和交易员发帖时机量化: 发帖前已涨多少 / 发帖后跟单还有多少肉。
数据: Temp/sanhe/all_posts.json (120帖) + 币安日K (经本地代理拉取)。
跟单模拟: 发帖次日(UTC)开盘进场, 看 +1/+3/+7 日收盘收益与7日内最大冲高/回撤。
"""
import json, time, datetime, urllib.request, collections, sys

POSTS = r'C:\Users\Administrator\AppData\Local\Temp\sanhe\all_posts.json'
PROXY = urllib.request.ProxyHandler({'https': 'http://127.0.0.1:7897'})
OPENER = urllib.request.build_opener(PROXY)
SKIP = {'CRCLon', 'COINon', 'BABAon', '币安人生USDT'}

def fetch_klines(sym):
    url = f'https://fapi.binance.com/fapi/v1/klines?symbol={sym}&interval=1d&limit=150'
    try:
        raw = json.loads(OPENER.open(url, timeout=10).read())
    except Exception as e:
        print(f'  ! {sym} 拉取失败: {e}', file=sys.stderr)
        return None
    return [dict(t=int(k[0]), o=float(k[1]), h=float(k[2]), l=float(k[3]), c=float(k[4])) for k in raw]

def utc_date(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).date()

def main():
    posts = json.load(open(POSTS, encoding='utf-8'))
    posts.sort(key=lambda p: p['time'])
    syms = sorted({pr for p in posts for pr in (p.get('pairs') or []) if pr not in SKIP})
    print(f'拉取 {len(syms)} 个标的日K...', file=sys.stderr)
    kl = {}
    for s in syms:
        k = fetch_klines(s)
        if k:
            kl[s] = {utc_date(x['t']): (i, x) for i, x in enumerate(k)}, k
        time.sleep(0.15)

    seen = set()
    rows = []
    for p in posts:
        for sym in (p.get('pairs') or []):
            if sym not in kl: continue
            idx, arr = kl[sym]
            d = utc_date(p['time'])
            if d not in idx: continue
            i, today = idx[d]
            first = sym not in seen
            seen.add(sym)
            row = dict(sym=sym.replace('USDT',''), date=str(d), first=first,
                       view=p.get('view', 0), pid=p['id'])
            # 发帖前涨幅 (相对当日收盘)
            for n, key in [(7, 'pre7'), (14, 'pre14')]:
                row[key] = (today['c'] / arr[i-n]['c'] - 1) * 100 if i - n >= 0 else None
            # 跟单: 次日开盘进场
            if i + 1 < len(arr):
                e = arr[i+1]['o']
                if e > 0:
                    row['entry'] = e
                    for n, key in [(1, 'fwd1'), (3, 'fwd3'), (7, 'fwd7')]:
                        j = i + n
                        row[key] = (arr[j]['c'] / e - 1) * 100 if j < len(arr) else None
                    w = arr[i+1: i+8]
                    row['maxup7'] = (max(x['h'] for x in w) / e - 1) * 100
                    row['maxdd7'] = (min(x['l'] for x in w) / e - 1) * 100
            rows.append(row)

    json.dump(rows, open(r'C:\Users\Administrator\AppData\Local\Temp\sanhe\post_timing.json', 'w'), ensure_ascii=False)

    def f(v, w=8):
        return f'{v:>{w}.1f}' if isinstance(v, float) else ' ' * (w - 4) + 'n/a '

    def agg(rs, label):
        if not rs: return
        def avg(k):
            vs = [r[k] for r in rs if r.get(k) is not None]
            return sum(vs) / len(vs) if vs else None
        def med(k):
            vs = sorted(r[k] for r in rs if r.get(k) is not None)
            return vs[len(vs)//2] if vs else None
        wr7 = [r for r in rs if r.get('fwd7') is not None]
        wr = sum(1 for r in wr7 if r['fwd7'] > 0) / len(wr7) * 100 if wr7 else 0
        print(f'{label:<24} n={len(rs):<4} 前7日均涨{f(avg("pre7"))}% 中位{f(med("pre7"))}% | '
              f'跟单后: +1d{f(avg("fwd1"))}% +3d{f(avg("fwd3"))}% +7d{f(avg("fwd7"))}%(胜率{wr:.0f}%) '
              f'冲高{f(avg("maxup7"))}% 回撤{f(avg("maxdd7"))}%')

    print('\n========== 总体 ==========')
    agg(rows, '全部帖子')
    agg([r for r in rows if r['first']], '首次提及')
    agg([r for r in rows if not r['first']], '后续追踪帖')
    majors = {'BTC', 'ETH', 'BNB', 'XLM', 'ZEC', 'HYPE'}
    agg([r for r in rows if r['first'] and r['sym'] not in majors], '首次提及(剔除主流币)')

    print('\n========== 首次提及明细 (按日期) ==========')
    print(f"{'标的':<10}{'日期':<12}{'前7日%':>8}{'前14日%':>9}{'+1d%':>8}{'+3d%':>8}{'+7d%':>8}{'冲高%':>8}{'回撤%':>8}")
    for r in rows:
        if not r['first']: continue
        print(f"{r['sym']:<10}{r['date']:<12}{f(r.get('pre7'))}{f(r.get('pre14'),9)}"
              f"{f(r.get('fwd1'))}{f(r.get('fwd3'))}{f(r.get('fwd7'))}{f(r.get('maxup7'))}{f(r.get('maxdd7'))}")

if __name__ == '__main__':
    main()
