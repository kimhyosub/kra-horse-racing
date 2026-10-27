"""시장+요인 조건부 로짓 (우승마 기준)
학습: 2023-07 ~ 2024-12 / 검증: 2025-01 ~ 2026-10
- 정확도: 검증기간 log-likelihood (시장 단독 vs 시장+요인)
- 가치 베팅: 모델확률 × 배당 ≥ 기준 인 말 단승, Harville 복승
"""
import math, random, itertools, collections
import numpy as np, pandas as pd
from scipy.optimize import minimize

D = '/home/claude/api/'
r = pd.read_pickle(D + 'analysis_frame.pkl')
q = pd.read_csv(D + 'q.csv', encoding='utf-8-sig', dtype=str)
q['rk'] = q['rcDate'] + '-' + q['rcNo']
q['a'] = q[['chulNo1', 'chulNo2']].astype(int).min(axis=1); q['b'] = q[['chulNo1', 'chulNo2']].astype(int).max(axis=1)
QODDS = {(k, a, b): float(o) for k, a, b, o in zip(q['rk'], q['a'], q['b'], q['odds'])}

r['log_days'] = np.log1p(r['days'].fillna(35))
r['first'] = (r['n_prev'] == 0).astype(float)
r['risky_f'] = r['risky'].astype(float); r['stable_f'] = r['stable'].astype(float)
r['ref_last_f'] = r['ref_last'].fillna(0); r['tool_change_f'] = r['tool_change'].fillna(0)
r['wet_early'] = np.where(r['wet'], -r['early4'].fillna(0.5), 0)
FEATS = ['early4', 'g1f4', 'behind3', 'best_behind', 'form3', 'place_rate', 'dist_place', 'log_days', 'first',
         'bwd', 'risky_f', 'stable_f', 'jk_rate', 'tr_rate', 'tool_change_f', 'jk_change', 'cls_change', 'dist_change',
         'ref_last_f', 'ref_180', 'budam_change', 'wgBudam', 'age', 'rating', 'wet_early']
BIN = {'first', 'risky_f', 'stable_f', 'tool_change_f', 'jk_change', 'ref_last_f'}

def build(df):
    out = []
    for k, d in df.groupby('rk'):
        if d['win'].sum() != 1 or len(d) < 5: continue
        X = np.zeros((len(d), len(FEATS)))
        for j, f in enumerate(FEATS):
            v = d[f].astype(float).values
            m = np.nanmean(v) if np.any(~np.isnan(v)) else 0.0
            v = np.where(np.isnan(v), m, v)
            sd = v.std()
            X[:, j] = v - v.mean() if (f in BIN or sd < 1e-9) else (v - v.mean()) / sd
        pm = d['p_mkt'].values
        out.append(dict(rk=k, X=X, lp=np.log(pm), win=int(np.argmax(d['win'].values)), d=d, year=int(d['year'].iloc[0])))
    return out

def nll(w, R, lam, use_feat=True):
    tot = 0.0
    for x in R:
        u = w[0] * x['lp'] + (x['X'] @ w[1:] if use_feat else 0)
        m = u.max(); tot -= u[x['win']] - (m + math.log(np.exp(u - m).sum()))
    return tot + lam * np.sum(w[1:] ** 2)

def fit(R, lam):
    w0 = np.zeros(1 + len(FEATS)); w0[0] = 1.0
    return minimize(nll, w0, args=(R, lam), method='L-BFGS-B').x

def probs(x, w, use_feat=True):
    u = w[0] * x['lp'] + (x['X'] @ w[1:] if use_feat else 0)
    p = np.exp(u - u.max()); return p / p.sum()

def harville2(p):
    out = {}
    n = len(p)
    for i in range(n):
        for j in range(i + 1, n):
            out[(i, j)] = p[i] * p[j] / (1 - p[i]) + p[j] * p[i] / (1 - p[j])
    return out

def boot(vals, it=3000):
    if not vals: return (0, 0, 0)
    B = sorted(sum(random.choice(vals) for _ in vals) / len(vals) * 100 for _ in range(it))
    return B[int(it * .025)], B[int(it * .975)], sum(b < 100 for b in B) / it * 100

if __name__ == '__main__':
    random.seed(1)
    R = build(r)
    tr = [x for x in R if x['d']['date'].iloc[0] < pd.Timestamp('2025-01-01')]
    te = [x for x in R if x['d']['date'].iloc[0] >= pd.Timestamp('2025-01-01')]
    print('학습 경주', len(tr), '/ 검증 경주', len(te))
    # 시장 단독 (온도 보정만)
    wm = minimize(lambda w: nll(np.r_[w, np.zeros(len(FEATS))], tr, 0, False), [1.0], method='L-BFGS-B').x
    print('시장 보정계수 %.3f' % wm[0])
    res = {}
    for lam in (1.0, 5.0, 20.0):
        w = fit(tr, lam)
        ll_m = -nll(np.r_[wm, np.zeros(len(FEATS))], te, 0, False) / len(te)
        ll_f = -nll(w, te, 0, True) / len(te)
        res[lam] = w
        print(f'λ={lam:5.1f}  검증 logL/경주: 시장 {ll_m:.4f}  시장+요인 {ll_f:.4f}  차이 {ll_f - ll_m:+.4f}')
    lam = max(res, key=lambda L: -nll(res[L], te, 0, True))
    w = res[lam]
    print(f'\n선택 λ={lam}  (검증 기준 — 참고: λ 선택에 검증셋을 쓴 만큼 약간 낙관적)')
    print('시장 계수 %.3f' % w[0])
    for f, c in sorted(zip(FEATS, w[1:]), key=lambda t: -abs(t[1])): print(f'  {f:14s} {c:+.3f}')

    # ------- 가치 베팅 시뮬레이션 (검증기간) -------
    print('\n### 가치 베팅 (검증 2025-01~2026-10, 1회 1단위)')
    for th in (1.0, 1.1, 1.2, 1.3, 1.5):
        for cap in (20, 50):
            vals = []
            for x in te:
                p = probs(x, w); d = x['d']
                for i in range(len(d)):
                    od = d['winOdds'].iloc[i]
                    if od <= cap and p[i] * od >= th:
                        vals.append(od if i == x['win'] else 0.0)
            lo, hi, pl = boot(vals)
            print(f'  단승 EV≥{th:.1f}, 배당≤{cap:3d}: 베팅 {len(vals):5d}  적중 {sum(v > 0 for v in vals):4d}  회수율 {sum(vals) / max(len(vals), 1) * 100:5.1f}%  95%구간 {lo:4.0f}~{hi:4.0f}%  100%미만확률 {pl:3.0f}%')
    for th in (1.0, 1.2, 1.5, 2.0):
        vals = []
        for x in te:
            p = probs(x, w); d = x['d']; nos = d['chulNo'].astype(int).values
            fin = d['ord'].values
            top2 = tuple(sorted(nos[np.argsort(fin)][:2]))
            for (i, j), ph in harville2(p).items():
                a, b = sorted((nos[i], nos[j]))
                od = QODDS.get((x['rk'], a, b))
                if od is None or od > 100: continue
                if ph * od >= th:
                    vals.append(od if (a, b) == top2 else 0.0)
        lo, hi, pl = boot(vals)
        print(f'  복승 EV≥{th:.1f}, 배당≤100: 베팅 {len(vals):6d}  적중 {sum(v > 0 for v in vals):4d}  회수율 {sum(vals) / max(len(vals), 1) * 100:5.1f}%  95%구간 {lo:4.0f}~{hi:4.0f}%  100%미만확률 {pl:3.0f}%')
    # 비교: 시장 확률로 같은 규칙 (모델 없이)
    vals = []
    for x in te:
        d = x['d']; i = int(np.argmin(d['winOdds'].values)); od = d['winOdds'].iloc[i]
        vals.append(od if i == x['win'] else 0.0)
    print('  (기준) 인기1위 단승: 회수율 %.1f%%' % (sum(vals) / len(vals) * 100))
    import json
    json.dump({'feats': FEATS, 'w': list(map(float, w)), 'lambda': lam}, open(D + 'model_weights.json', 'w'), ensure_ascii=False, indent=1)
