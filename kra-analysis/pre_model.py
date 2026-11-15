"""전날 예상 모델 (배당 없이 요인만으로 우승 확률)

학습: ~2025-06 / 검증: 2025-07 ~
입력: feat_all.pkl (api_features 결과, 전 경마장)
출력: kra-raceday/model_weights_pre.json  {"feats": [...], "w": [...]}

당일 모델(api_model.py)과 같은 요인 정의를 쓰되,
전날에는 알 수 없는 배당·마체중(bwd, risky_f, stable_f)·주로 상태(wet_early)는 뺀다.
"""
import json, sys
import numpy as np, pandas as pd
from scipy.optimize import minimize

SRC = sys.argv[1] if len(sys.argv) > 1 else '/home/claude/api2/feat_all.pkl'
OUT = sys.argv[2] if len(sys.argv) > 2 else '../kra-raceday/model_weights_pre.json'
LAM = 20.0

FEATS = ['early4', 'g1f4', 'behind3', 'best_behind', 'form3', 'place_rate', 'dist_place', 'log_days', 'first',
         'jk_rate', 'tr_rate', 'tool_change_f', 'jk_change', 'cls_change', 'dist_change',
         'ref_last_f', 'ref_180', 'budam_change', 'wgBudam', 'age', 'rating']
BIN = {'first', 'tool_change_f', 'jk_change', 'ref_last_f'}

r = pd.read_pickle(SRC)
r['log_days'] = np.log1p(r['days'].fillna(35))
r['first'] = (r['n_prev'] == 0).astype(float)
r['ref_last_f'] = r['ref_last'].fillna(0)
r['tool_change_f'] = r['tool_change'].fillna(0)
r['rid'] = r['src_meet'].astype(str) + '_' + r['rcDate'].astype(str) + '_' + r['rcNo'].astype(str)
for f in FEATS:
    r[f] = pd.to_numeric(r[f], errors='coerce').astype(float)


def build(df):
    out = []
    for k, d in df.groupby('rid'):
        if d['win'].sum() != 1 or len(d) < 5:
            continue
        X = np.zeros((len(d), len(FEATS)))
        for j, f in enumerate(FEATS):
            v = d[f].values
            m = np.nanmean(v) if np.any(~np.isnan(v)) else 0.0
            v = np.where(np.isnan(v), m, v)
            sd = v.std()
            X[:, j] = v - v.mean() if (f in BIN or sd < 1e-9) else (v - v.mean()) / sd
        out.append(dict(X=X, win=int(np.argmax(d['win'].values)), d=d, date=int(d['rcDate'].iloc[0])))
    return out


def nll(w, R):
    tot = 0.0
    for x in R:
        u = x['X'] @ w
        m = u.max()
        tot -= u[x['win']] - (m + np.log(np.exp(u - m).sum()))
    return tot + LAM * np.sum(w ** 2)


def evaluate(R, w):
    m1 = m5 = k1 = k5 = t3m = t3k = 0
    for x in R:
        d = x['d']
        u = x['X'] @ w
        top = np.argsort(-u)
        mk = np.argsort(-d['p_mkt'].fillna(0).values)
        ords = pd.to_numeric(d['ord'], errors='coerce').values
        top3 = set(np.where(ords <= 3)[0])
        m1 += top[0] == x['win']; m5 += x['win'] in top[:5]; t3m += len(top3 & set(top[:5]))
        k1 += mk[0] == x['win']; k5 += x['win'] in mk[:5]; t3k += len(top3 & set(mk[:5]))
    n = len(R)
    print('검증 경주 %d' % n)
    print('전날 모델  1위 적중 %.1f%%  상위5두 안 우승마 %.1f%%  상위5두 안 1~3착 %.2f두' % (m1 / n * 100, m5 / n * 100, t3m / n))
    print('배당 순서  1위 적중 %.1f%%  상위5두 안 우승마 %.1f%%  상위5두 안 1~3착 %.2f두' % (k1 / n * 100, k5 / n * 100, t3k / n))


if __name__ == '__main__':
    R = build(r)
    tr = [x for x in R if x['date'] < 20250701]
    te = [x for x in R if x['date'] >= 20250701]
    print('학습 %d / 검증 %d' % (len(tr), len(te)))
    w = minimize(nll, np.zeros(len(FEATS)), args=(tr,), method='L-BFGS-B').x
    evaluate(te, w)
    json.dump({'feats': FEATS, 'bin': sorted(BIN), 'w': [round(float(v), 6) for v in w],
               'note': '전날 예상 모델: 배당 없이 요인만. 학습 ~2025-06, 검증 2025-07~'},
              open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('저장', OUT)
