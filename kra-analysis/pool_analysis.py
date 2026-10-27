"""승식별 검증: 공제율(환급률), 인기도별 회수율, 단승배당 기반 Harville 확률 대비 고평가/저평가"""
import pandas as pd, numpy as np, itertools, math, sys
O = pd.read_pickle('/home/claude/api3/odds.pkl')
O['rk'] = O['src_meet'] + '-' + O['rcDate'] + '-' + O['rcNo'].astype(int).astype(str)
r = pd.read_csv('/home/claude/api2/race.csv', encoding='utf-8-sig', dtype=str,
                usecols=['src_meet', 'rcDate', 'rcNo', 'chulNo', 'ord', 'ordBigo', 'winOdds'])
r['rk'] = r['src_meet'] + '-' + r['rcDate'] + '-' + r['rcNo']
r = r[r['rk'].isin(set(O['rk']))]
bad = r.groupby('rk')['ordBigo'].transform(lambda s: s.isin(['경주취소', '경주불성립']).any())
r = r[~bad]
r = r[~r['ordBigo'].isin(['출전취소', '경주제외'])].copy()
for c in ['chulNo', 'ord', 'winOdds']: r[c] = pd.to_numeric(r[c], errors='coerce')
r = r[r['winOdds'] > 0]
races = {}
for rk, g in r.groupby('rk'):
    fin = g[(g['ord'] > 0) & (g['ordBigo'] != '주행중지')].sort_values('ord')
    if len(fin) < 3: continue
    races[rk] = dict(n=len(g), o=fin['chulNo'].astype(int).tolist(), ordv=fin['ord'].tolist(),
                     wo=dict(zip(g['chulNo'].astype(int), g['winOdds'])))
O = O[O['rk'].isin(races)].copy()
POOLS = ['단승식', '연승식', '복승식', '쌍승식', '복연승식', '삼복승식', '삼쌍승식']
NM = {'단승식': '단승', '연승식': '연승', '복승식': '복승', '쌍승식': '쌍승', '복연승식': '복연', '삼복승식': '삼복', '삼쌍승식': '삼쌍'}

def winners(rc, pool):
    o = rc['o']; a, b, c = o[0], o[1], o[2]
    if pool == '단승식': return {(a,)}
    if pool == '연승식': return {(x,) for x in (o[:2] if rc['n'] <= 7 else o[:3])}
    if pool == '복승식': return {tuple(sorted((a, b)))}
    if pool == '쌍승식': return {(a, b)}
    if pool == '복연승식': return {tuple(sorted(p)) for p in itertools.combinations((a, b, c), 2)}
    if pool == '삼복승식': return {tuple(sorted((a, b, c)))}
    if pool == '삼쌍승식': return {(a, b, c)}

def harville(rc, pool, key):
    wo = rc['wo']; inv = {k: 1 / v for k, v in wo.items()}; s = sum(inv.values()); p = {k: v / s for k, v in inv.items()}
    def seq(t):
        pr, rem = 1.0, 1.0
        for h in t:
            if h not in p: return np.nan
            pr *= p[h] / rem; rem -= p[h]
        return pr
    if pool == '단승식': return p.get(key[0], np.nan)
    if pool in ('쌍승식', '삼쌍승식'): return seq(key)
    if pool in ('복승식', '삼복승식'): return sum(seq(t) for t in itertools.permutations(key))
    if pool == '복연승식':  # 두 마리 모두 3착 안
        x, y = key; others = [h for h in p if h not in key]
        return sum(seq(t) for t in itertools.permutations(key)) + sum(seq(t) for z in others for t in [(x, z, y), (z, x, y), (y, z, x), (z, y, x)])
    if pool == '연승식':
        h = key[0]; k = 2 if rc['n'] <= 7 else 3; others = [z for z in p if z != h]
        tot = p[h]
        for z in others: tot += seq((z, h))
        if k == 3:
            for z1, z2 in itertools.permutations(others, 2): tot += seq((z1, z2, h))
        return tot

rows = []
for (rk, pool), g in O.groupby(['rk', 'pool']):
    rc = races[rk]; W = winners(rc, pool)
    keys = [tuple(int(v) for v in t if v > 0) for t in zip(g['chulNo'], g['chulNo2'], g['chulNo3'])]
    for k, od in zip(keys, g['odds']):
        rows.append((rk, pool, od, float(k in W), k))
D = pd.DataFrame(rows, columns=['rk', 'pool', 'odds', 'hit', 'key'])
D['year'] = D['rk'].str[2:6]
print(f"경주 {len(races)} (서울 {D['rk'].str[2:6].min()}~{D['rk'].str[2:6].max()}), 조합 {len(D):,}")

print('\n### 1. 승식별 기본 — 전 조합 균등 구매 회수율 / 적중 조합 배당')
print(f"{'승식':4s} {'조합/경주':>8s} {'적중률':>7s} {'전조합균등':>9s} {'적중배당 중앙':>11s} {'9999.9캡 비율':>12s}")
for p in POOLS:
    d = D[D['pool'] == p]; h = d[d['hit'] == 1]
    print(f"{NM[p]:4s} {len(d) / d['rk'].nunique():8.1f} {d['hit'].mean() * 100:6.2f}% {h['odds'].sum() / len(d) * 100:8.1f}% "
          f"{h['odds'].median():11.1f} {(d['odds'] >= 9999.9).mean() * 100:11.1f}%")

print('\n### 2. 배당 구간별 회수율 (같은 구간 조합을 모두 1씩 샀을 때) — 인기 쪽이 덜 손해인가')
BANDS = {'단승식': [1, 3, 7, 15, 30, 9e9], '연승식': [1, 1.5, 2.5, 5, 10, 9e9], '복승식': [1, 5, 15, 40, 100, 9e9],
         '쌍승식': [1, 10, 30, 80, 200, 9e9], '복연승식': [1, 3, 8, 20, 50, 9e9], '삼복승식': [1, 20, 60, 200, 800, 9e9], '삼쌍승식': [1, 80, 300, 1000, 4000, 9e9]}
for p in POOLS:
    d = D[D['pool'] == p]; b = BANDS[p]
    cut = pd.cut(d['odds'], b, right=False)
    s = d.groupby(cut, observed=True).apply(lambda x: pd.Series({'n': len(x), 'hit': x['hit'].sum(), 'roi': (x['odds'] * x['hit']).sum() / len(x) * 100}))
    print(f"{NM[p]:4s} " + ' | '.join(f"{str(i.left).rstrip('0').rstrip('.')}~{'' if i.right > 1e8 else str(i.right).rstrip('0').rstrip('.')}: {v.roi:5.1f}% ({int(v.hit)}적중)" for i, v in s.iterrows()))

print('\n### 3. 단승배당으로 계산한 확률(Harville) × 실제 배당 = 기대값. 기대값 높은 조합이 실제로 더 남는가 (모델 학습 없음)')
D['ph'] = [harville(races[rk], p, k) for rk, p, k in zip(D['rk'], D['pool'], D['key'])]
D['ev'] = D['ph'] * D['odds']
D.to_pickle('/home/claude/api3/pool_rows.pkl')
for p in POOLS[1:]:
    d = D[(D['pool'] == p) & D['ev'].notna()]
    cut = pd.cut(d['ev'], [0, .5, .7, .85, 1.0, 1.2, 9e9], right=False)
    s = d.groupby(cut, observed=True).apply(lambda x: pd.Series({'n': len(x), 'hit': x['hit'].sum(), 'roi': (x['odds'] * x['hit']).sum() / len(x) * 100}))
    print(f"{NM[p]:4s} " + ' | '.join(f"EV{i.left:g}~{'' if i.right > 1e8 else f'{i.right:g}'}: {v.roi:5.1f}% (n{int(v.n)}, {int(v.hit)}적중)" for i, v in s.iterrows()))
