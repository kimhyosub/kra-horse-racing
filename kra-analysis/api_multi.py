"""경마장별 검증: 서울에서 찾은 신호가 부경·제주에서도 재현되는가 (독립 시장 = 진짜 검증)"""
import math, random, json, sys, itertools
import numpy as np, pandas as pd
sys.path.insert(0, '/home/claude/tools')
import api_model as M

r = pd.read_pickle('/home/claude/api2/feat_all.pkl')
r = r[r['date'] >= '2023-07-01'].copy()
r['year'] = r['date'].dt.year
g = r.groupby('rk')
r['early_rank'] = g['early4'].rank(method='min')
r['risky'] = r['bwd'] >= 5
r['stable'] = r['bwd'].between(-4, 4) & (r['days'] <= 35)
NAMES = {1: '서울', 2: '제주', 3: '부경', 4: '영천'}

def z(sub):
    n = len(sub); w = sub['win'].sum(); e = sub['p_mkt'].sum(); v = (sub['p_mkt'] * (1 - sub['p_mkt'])).sum()
    return n, w, e, (w - e) / math.sqrt(v) if v > 0 else 0, sub.loc[sub['win'], 'winOdds'].sum() / max(n, 1) * 100

SIG = [('마체중 +5kg 이상', lambda d: d['risky']), ('안정마(±4,35일내)', lambda d: d['stable']),
       ('50배 이상', lambda d: d['winOdds'] >= 50), ('7~12배', lambda d: d['winOdds'].between(7, 11.99)),
       ('~2배', lambda d: d['winOdds'] < 2),
       ('직전 경주 제재', lambda d: d['ref_last'] == 1), ('하위 기수(~6%)', lambda d: d['jk_rate'] < .06),
       ('하위 조교사(~6%)', lambda d: d['tr_rate'] < .06), ('선행력 1위', lambda d: d['early_rank'] == 1),
       ('2~3세', lambda d: d['age'] <= 3), ('6세 이상', lambda d: d['age'] >= 6), ('장구 변경', lambda d: d['tool_change'] == 1)]

print('### 경마장별 기본')
for mt in [1, 3, 2, 4]:
    d = r[r['meetc'] == mt]
    if d.empty: continue
    fav = d.loc[d.groupby('rk')['winOdds'].idxmin()]
    print(f"{NAMES[mt]}: {d['rk'].nunique()}경주 {len(d)}두 | 전 출주마 단승 회수율 {d.loc[d['win'], 'winOdds'].sum() / len(d) * 100:.1f}% | "
          f"인기1위 승률 {fav['win'].mean() * 100:.1f}% 회수율 {fav.loc[fav['win'], 'winOdds'].sum() / len(fav) * 100:.1f}%")

print('\n### 신호별 (실제승/시장기대, z, 단승회수율) — 서울에서 찾은 신호가 다른 경마장에서도 같은 방향인가')
print(f"{'신호':18s}" + ''.join(f" | {NAMES[mt]:^30s}" for mt in [1, 3, 2]))
for name, sel in SIG:
    line = f'{name:18s}'
    for mt in [1, 3, 2]:
        d = r[r['meetc'] == mt]; s = d[sel(d)]
        n, w, e, zz, roi = z(s)
        line += f" | {w:5d}/{e:7.1f} {zz:+5.2f} {roi:4.0f}%"
    print(line)

# 모델: 경마장별 학습(2023H2~2024) → 검증(2025~2026), 그리고 서울 가중치를 그대로 적용
print('\n### 모델 (시장+요인) 경마장별 검증 2025~2026')
r['log_days'] = np.log1p(r['days'].fillna(35)); r['first'] = (r['n_prev'] == 0).astype(float)
r['risky_f'] = r['risky'].astype(float); r['stable_f'] = r['stable'].astype(float)
r['ref_last_f'] = r['ref_last'].fillna(0); r['tool_change_f'] = r['tool_change'].fillna(0)
r['wet_early'] = np.where(r['wet'], -r['early4'].fillna(0.5), 0)
w_seoul = np.array(json.load(open('/home/claude/api/model_weights.json'))['w'])
random.seed(3)
for mt in [1, 3, 2]:
    d = r[r['meetc'] == mt]
    R = M.build(d)
    tr = [x for x in R if x['d']['date'].iloc[0] < pd.Timestamp('2025-01-01')]
    te = [x for x in R if x['d']['date'].iloc[0] >= pd.Timestamp('2025-01-01')]
    wm = np.r_[1.0, np.zeros(len(M.FEATS))]
    ll_m = -M.nll(wm, te, 0, False) / len(te)
    w_own = M.fit(tr, 20.0)
    for label, w in [('자체 학습', w_own), ('서울 가중치', w_seoul)]:
        ll = -M.nll(w, te, 0, True) / len(te)
        line = f"{NAMES[mt]} {label:7s}: logL 시장 {ll_m:.4f} → 모델 {ll:.4f} ({ll - ll_m:+.4f})"
        for th, cap in [(1.0, 20), (1.1, 20), (1.2, 20)]:
            vals = []
            for x in te:
                p = M.probs(x, w); dd = x['d']
                for i in range(len(dd)):
                    od = dd['winOdds'].iloc[i]
                    if od <= cap and p[i] * od >= th: vals.append(od if i == x['win'] else 0.0)
            lo, hi, pl = M.boot(vals, 1500)
            line += f" | EV≥{th}: {len(vals)}회 {sum(vals) / max(len(vals), 1) * 100:.0f}% [{lo:.0f}~{hi:.0f}]"
        print(line)
    if mt != 1:
        json.dump({'feats': M.FEATS, 'w': list(map(float, w_own)), 'meet': mt}, open(f'/home/claude/api2/model_weights_meet{mt}.json', 'w'), ensure_ascii=False)
