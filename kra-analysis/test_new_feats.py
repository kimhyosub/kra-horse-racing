import sys, random, numpy as np, pandas as pd
sys.path.insert(0, '/home/claude/tools')
import api_model as M
random.seed(5)
r = pd.read_pickle('/home/claude/api2/feat_all_v6.pkl')
r = r[r['date'] >= '2023-07-01'].copy(); r['year'] = r['date'].dt.year
r['risky'] = r['bwd'] >= 5; r['stable'] = r['bwd'].between(-4, 4) & (r['days'] <= 35)
r['log_days'] = np.log1p(r['days'].fillna(35)); r['first'] = (r['n_prev'] == 0).astype(float)
r['risky_f'] = r['risky'].astype(float); r['stable_f'] = r['stable'].astype(float)
r['ref_last_f'] = r['ref_last'].fillna(0); r['tool_change_f'] = r['tool_change'].fillna(0)
r['wet_early'] = np.where(r['wet'], -r['early4'].fillna(0.5), 0)
BASE = list(M.FEATS); NEW = ['jt_combo', 'instab', 'opp_level', 'front_comp', 's1f4']
VARS = [('기존', BASE)] + [(f'+{f}', BASE + [f]) for f in NEW] + [('+5개 전부', BASE + NEW)]
NM = {1: '서울', 3: '부경', 2: '제주'}
def ev_roi(te, w):
    v = []
    for x in te:
        p = M.probs(x, w); d = x['d']
        for i in range(len(d)):
            od = d['winOdds'].iloc[i]
            if od <= 20 and p[i] * od >= 1.1: v.append(od if i == x['win'] else 0.0)
    return len(v), sum(v) / max(len(v), 1) * 100
for mt in (1, 2, 3):
    d = r[r['meetc'] == mt]
    print(f'\n### {NM[mt]}  (학습 2023H2~2024 → 검증 2025~2026)')
    base_ll = None
    for name, feats in VARS:
        M.FEATS = feats
        R = M.build(d)
        tr = [x for x in R if x['year'] < 2025]; te = [x for x in R if x['year'] >= 2025]
        w = M.fit(tr, 20.0)
        ll = -M.nll(w, te, 0, True) / len(te)
        if base_ll is None: base_ll = ll
        n, roi = ev_roi(te, w)
        coef = '' if name in ('기존', '+5개 전부') else f'  계수 {w[-1]:+.3f}'
        print(f'  {name:12s} 검증 logL {ll:.5f} (기존 대비 {ll - base_ll:+.5f}) | EV≥1.1: {n:4d}회 {roi:5.1f}%{coef}')
