"""점검: 출전 기록이 적은 말에게 전날 예상이 확률을 너무 높게 주는가

  python3 kra-analysis/pre_sample_check.py [작업 폴더]   (pre_weight_test.py 가 만든 ~/pre-test/feat_m*.pkl 을 다시 쓴다)

실제 운영 중인 전날 예상 가중치(kra-raceday/model_weights_pre.json, 학습 ~2025-06)로
학습에 안 쓴 2025-07 이후 경주의 확률을 다시 계산하고, 그 말의 이전 출전 수별로
  모델이 준 확률의 합(=예상 우승 수) vs 실제 우승 수  를 비교한다.
  실제/예상 < 1 이면 그 무리를 너무 높게 본 것. ±2σ 밖이면 우연으로 보기 어렵다.
가치마 1위로 꼽힌 말만 따로, 확률 20% 이상만 따로도 본다.
"""
import json, os, sys
import numpy as np, pandas as pd

WORK = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser('~/pre-test')
HERE = os.path.dirname(os.path.abspath(__file__))
W = json.load(open(os.path.join(HERE, '..', 'kra-raceday', 'model_weights_pre.json'), encoding='utf-8'))
FEATS, BIN, w = W['feats'], set(W['bin']), np.array(W['w'])
CUT = 20250701
NAME = {1: '서울', 2: '제주', 3: '부경'}

parts = []
for m in (1, 2, 3):
    d = pd.read_pickle(os.path.join(WORK, f'feat_m{m}.pkl')); d['meet'] = m; parts.append(d)
r = pd.concat(parts, ignore_index=True)
r['log_days'] = np.log1p(r['days'].fillna(35))
r['first'] = (r['n_prev'] == 0).astype(float)
r['ref_last_f'] = r['ref_last'].fillna(0)
r['tool_change_f'] = r['tool_change'].fillna(0)
# 그 말의 이전 우승 수 (데이터 안에서)
r = r.sort_values(['hrNo', 'date', 'rcNo']).reset_index(drop=True)
r['prev_wins'] = r.groupby('hrNo')['win'].cumsum() - r['win']
r['rid'] = r['meet'].astype(str) + '_' + r['rcDate'].astype(str) + '_' + r['rcNo'].astype(str)
r['dnum'] = pd.to_numeric(r['rcDate'], errors='coerce')
r = r[r['dnum'] >= CUT]
ok = r.groupby('rid')['win'].transform('sum').eq(1) & r.groupby('rid')['win'].transform('size').ge(5)
r = r[ok].copy()

# 운영과 같은 방식: 경주마다 표준화(빈 값은 경주 평균) → 점수 → softmax
probs = np.zeros(len(r)); rank = np.zeros(len(r), int)
idx = r.groupby('rid').indices
for rid, ii in idx.items():
    X = np.zeros((len(ii), len(FEATS)))
    for j, f in enumerate(FEATS):
        v = pd.to_numeric(r[f].iloc[ii], errors='coerce').astype(float).values
        mm = np.nanmean(v) if np.any(~np.isnan(v)) else 0.0
        v = np.where(np.isnan(v), mm, v)
        sd = v.std()
        X[:, j] = v - v.mean() if (f in BIN or sd < 1e-9) else (v - v.mean()) / sd
    u = X @ w; e = np.exp(u - u.max()); p = e / e.sum()
    probs[ii] = p
    rank[ii] = (-p).argsort().argsort() + 1
r['p'] = probs; r['rank'] = rank
r['win'] = r['win'].astype(float)


def table(df, title):
    print(title)
    print(f'{"":>16} {"출주":>7} {"예상 우승":>9} {"실제 우승":>9} {"실제/예상":>9} {"±2σ":>7}  판정')
    for lab, x in df:
        if len(x) < 30: continue
        e = x['p'].sum(); a = x['win'].sum(); sd = np.sqrt((x['p'] * (1 - x['p'])).sum())
        z = (a - e) / sd if sd > 0 else 0
        mark = '너무 높게 봄' if z < -2 else ('너무 낮게 봄' if z > 2 else '맞음')
        print(f'{lab:>16} {len(x):>7,} {e:>9.1f} {a:>9.0f} {a / e:>9.2f} {2 * sd / e:>6.0%}  {mark}')
    print()


bins = [-1, 0, 1, 2, 4, 9, 999]
labs = ['데뷔(0전)', '1전', '2전', '3~4전', '5~9전', '10전 이상']
r['nb'] = pd.cut(r['n_prev'], bins, labels=labs)
print(f'검증 기간 2025-07~ 경주 {r["rid"].nunique():,}개, 출주 {len(r):,}건 (운영 중인 전날 예상 가중치)\n')
table(r.groupby('nb', observed=True), '1) 이전 출전 수별 — 모든 말')
table(r[r['rank'] == 1].groupby('nb', observed=True), '2) 가치마 1위로 꼽힌 말만')
table(r[r['p'] >= 0.20].groupby('nb', observed=True), '3) 확률 20% 이상 준 말만')
few = r[r['n_prev'].between(1, 2)].copy()
few['wins'] = np.where(few['prev_wins'] > 0, '이긴 적 있음', '이긴 적 없음')
table(few.groupby('wins'), '4) 1~2전 말: 이전에 이긴 적이 있나')
for m in (1, 2, 3):
    table(r[r['meet'] == m].groupby('nb', observed=True), f'5) {NAME[m]}')
