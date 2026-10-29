"""조합 승식 검증 (2025~2026, 학습에 쓰지 않은 기간)
말별 우승확률(시장 / 모델) → Harville 로 조합 확률 → × 실제 확정배당 = 기대값
비교: 같은 규칙을 시장 확률로 했을 때 vs 모델 확률로 했을 때"""
import sys, json, itertools, random
import numpy as np, pandas as pd
sys.path.insert(0, '/home/claude/tools')
import api_model as M
random.seed(7)
r = pd.read_pickle('/home/claude/api2/feat_all.pkl')
r = r[r['date'] >= '2025-01-01'].copy()
r['year'] = r['date'].dt.year
r['risky'] = r['bwd'] >= 5; r['stable'] = r['bwd'].between(-4, 4) & (r['days'] <= 35)
r['log_days'] = np.log1p(r['days'].fillna(35)); r['first'] = (r['n_prev'] == 0).astype(float)
r['risky_f'] = r['risky'].astype(float); r['stable_f'] = r['stable'].astype(float)
r['ref_last_f'] = r['ref_last'].fillna(0); r['tool_change_f'] = r['tool_change'].fillna(0)
r['wet_early'] = np.where(r['wet'], -r['early4'].fillna(0.5), 0)
W = {1: np.array(json.load(open('/home/claude/api/model_weights.json'))['w']),
     3: np.array(json.load(open('/home/claude/api/model_weights.json'))['w']),
     2: np.array(json.load(open('/home/claude/api2/model_weights_meet2.json'))['w'])}
O = pd.read_pickle('/home/claude/api4/odds_test.pkl')
O['rk'] = O['src_meet'] + '-' + O['rcDate'] + '-' + O['rcNo'].astype(int).astype(str)
OG = dict(tuple(O.groupby('rk')))
NAMES = {1: '서울', 3: '부경', 2: '제주'}
POOLS = ['단승식', '연승식', '복승식', '쌍승식', '복연승식', '삼복승식', '삼쌍승식']
NM = {'단승식': '단승', '연승식': '연승', '복승식': '복승', '쌍승식': '쌍승', '복연승식': '복연', '삼복승식': '삼복', '삼쌍승식': '삼쌍'}
CAP = {'단승식': 20, '연승식': 10, '복승식': 50, '쌍승식': 100, '복연승식': 30, '삼복승식': 300, '삼쌍승식': 2000}

def combo_probs(p, nos):
    """Harville: 말 i 1착 확률 p → 각 승식 조합 확률"""
    n = len(p); idx = range(n); P = {}
    s2 = {(i, j): p[i] * p[j] / (1 - p[i]) for i in idx for j in idx if i != j}
    s3 = {(i, j, k): s2[(i, j)] * p[k] / (1 - p[i] - p[j]) for i in idx for j in idx for k in idx if len({i, j, k}) == 3}
    top3 = collections_counter = {}
    for (i, j, k), v in s3.items():
        for h in (i, j, k): top3[h] = top3.get(h, 0) + v
    top2 = {}
    for (i, j), v in s2.items():
        for h in (i, j): top2[h] = top2.get(h, 0) + v
    for i in idx:
        P[('단승식', (nos[i],))] = p[i]
        P[('연승식', (nos[i],))] = top2.get(i, 0) if n <= 7 else top3.get(i, 0)
    for (i, j), v in s2.items():
        P[('쌍승식', (nos[i], nos[j]))] = v
        a, b = sorted((nos[i], nos[j])); P[('복승식', (a, b))] = P.get(('복승식', (a, b)), 0) + v
    for (i, j, k), v in s3.items():
        P[('삼쌍승식', (nos[i], nos[j], nos[k]))] = v
        t = tuple(sorted((nos[i], nos[j], nos[k]))); P[('삼복승식', t)] = P.get(('삼복승식', t), 0) + v
        for a, b in itertools.combinations(t, 2): P[('복연승식', (a, b))] = P.get(('복연승식', (a, b)), 0) + v
    return P

rows = []
for mt in (1, 3, 2):
    R = M.build(r[r['meetc'] == mt])
    for x in R:
        g = OG.get(x['rk'])
        if g is None: continue
        d = x['d']; nos = d['chulNo'].astype(int).tolist()
        fin = d[(d['ord'] > 0) & ~d['dnf']].sort_values('ord')['chulNo'].astype(int).tolist()
        if len(fin) < 3: continue
        a, b, c = fin[:3]; n = len(d)
        win = {'단승식': {(a,)}, '연승식': {(h,) for h in (fin[:2] if n <= 7 else fin[:3])}, '복승식': {tuple(sorted((a, b)))},
               '쌍승식': {(a, b)}, '복연승식': {tuple(sorted(t)) for t in itertools.combinations((a, b, c), 2)},
               '삼복승식': {tuple(sorted((a, b, c)))}, '삼쌍승식': {(a, b, c)}}
        pm = np.exp(x['lp']); pmod = M.probs(x, W[mt])
        PM, PD = combo_probs(pm, nos), combo_probs(pmod, nos)
        half = f"{d['date'].iloc[0].year % 100}{'H1' if d['date'].iloc[0].month <= 6 else 'H2'}"
        for pool, k1, k2, k3, od in zip(g['pool'], g['chulNo'], g['chulNo2'], g['chulNo3'], g['odds']):
            key = tuple(int(v) for v in (k1, k2, k3) if v > 0)
            pmk = PM.get((pool, key)); pdk = PD.get((pool, key))
            if pmk is None: continue
            rows.append((mt, half, x['rk'], pool, od, float(key in win[pool]), pmk, pdk))
D = pd.DataFrame(rows, columns=['meet', 'half', 'rk', 'pool', 'odds', 'hit', 'pm', 'pd'])
D.to_pickle('/home/claude/api4/pool_model_rows.pkl')
print('조합 수', len(D), D.groupby('meet')['rk'].nunique().to_dict())

def ev_rule(d, col, th, cap):
    s = d[(d[col] * d['odds'] >= th) & (d['odds'] <= cap)]
    vals = list(s['odds'] * s['hit'])
    lo, hi, _ = M.boot(vals, 800) if len(vals) > 20 else (0, 0, 0)
    return len(s), (s['odds'] * s['hit']).sum() / max(len(s), 1) * 100, lo, hi, s

print('\n### 1. 기대값 ≥ 1.1 (배당 상한 있음) 규칙: 시장확률 기준 vs 모델확률 기준. 회수율 [95% 범위]')
for mt in (1, 3, 2):
    print(f'-- {NAMES[mt]}')
    d = D[D['meet'] == mt]
    for p in POOLS:
        dp = d[d['pool'] == p]
        n1, r1, _, _, _ = ev_rule(dp, 'pm', 1.1, CAP[p])
        n2, r2, lo, hi, s = ev_rule(dp, 'pd', 1.1, CAP[p])
        hh = s.groupby('half').apply(lambda x: (x['odds'] * x['hit']).sum() / len(x) * 100)
        print(f"  {NM[p]:3s} (≤{CAP[p]}배) 시장 {n1:6d}회 {r1:5.1f}% | 모델 {n2:6d}회 {r2:5.1f}% [{lo:.0f}~{hi:.0f}] 반기별 " + ' '.join(f'{v:.0f}' for v in hh.values))
