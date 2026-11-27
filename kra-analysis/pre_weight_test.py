"""시험: 전날 예상 모델에 '직전 경주 마체중'을 넣으면 더 잘 맞는가

  python3 kra-analysis/pre_weight_test.py /srv/kra-data/race-result.csv /srv/kra-data/referee.csv [작업 폴더]

1) 경마장별로 api_features.py 를 돌려 경주 시점 특징값을 만든다 (처음 한 번 15분쯤, 작업 폴더에 저장해 두고 다시 쓴다)
2) 지금 전날 예상 요인(21개) vs 여기에 prev_bw(직전 경주 마체중, 경주 안에서 표준화)를 더한 것
   같은 방식(L2 20, 학습 ~2025-06, 검증 2025-07~)으로 학습해 검증 기간 성적을 비교한다
   - 로그우도(경주당, 높을수록 좋음), 1위 적중, 상위 5두 안 우승마
배당은 쓰지 않는다 (전날 예상이므로). 경주 당일 마체중도 전날에는 모르니 직전 경주 값만 쓴다.
"""
import os, sys, subprocess
import numpy as np, pandas as pd

RACE, REF = sys.argv[1], sys.argv[2]
WORK = sys.argv[3] if len(sys.argv) > 3 else os.path.expanduser('~/pre-test')
HERE = os.path.dirname(os.path.abspath(__file__))
os.makedirs(WORK, exist_ok=True)
NAME = {1: '서울', 2: '제주', 3: '부경'}
LAM = 20.0
CUT = 20250701

# ---------- 1) 특징값 ----------
raw = None
parts = []
for m in (1, 2, 3):
    pkl = os.path.join(WORK, f'feat_m{m}.pkl')
    if not os.path.exists(pkl):
        if raw is None:
            raw = pd.read_csv(RACE, encoding='utf-8-sig', dtype=str)
            if 'src_meet' not in raw.columns: raw['src_meet'] = '1'
        sub = os.path.join(WORK, f'race_m{m}.csv')
        raw[raw['src_meet'] == str(m)].to_csv(sub, index=False, encoding='utf-8-sig')
        print(f'[{NAME[m]}] 특징값 만드는 중 ...', flush=True)
        subprocess.run([sys.executable, os.path.join(HERE, 'api_features.py'), sub, REF, pkl[:-4]], check=True,
                       stdout=subprocess.DEVNULL)
    d = pd.read_pickle(pkl)
    d['meet'] = m
    parts.append(d)
r = pd.concat(parts, ignore_index=True)

BASE = ['early4', 'g1f4', 'behind3', 'best_behind', 'form3', 'place_rate', 'dist_place', 'log_days', 'first',
        'jk_rate', 'tr_rate', 'tool_change_f', 'jk_change', 'cls_change', 'dist_change',
        'ref_last_f', 'ref_180', 'budam_change', 'wgBudam', 'age', 'rating']
BIN = {'first', 'tool_change_f', 'jk_change', 'ref_last_f'}
r['log_days'] = np.log1p(r['days'].fillna(35))
r['first'] = (r['n_prev'] == 0).astype(float)
r['ref_last_f'] = r['ref_last'].fillna(0)
r['tool_change_f'] = r['tool_change'].fillna(0)
# 직전 경주 마체중 (같은 말의 바로 앞 기록)
r = r.sort_values(['hrNo', 'date', 'rcNo']).reset_index(drop=True)
r['prev_bw'] = pd.to_numeric(r['bw'], errors='coerce').groupby(r['hrNo']).shift(1)
r['rid'] = r['meet'].astype(str) + '_' + r['rcDate'].astype(str) + '_' + r['rcNo'].astype(str)
r['dnum'] = pd.to_numeric(r['rcDate'], errors='coerce')
for f in BASE + ['prev_bw']:
    r[f] = pd.to_numeric(r[f], errors='coerce').astype(float)
ok = r.groupby('rid')['win'].transform('sum').eq(1) & r.groupby('rid')['win'].transform('size').ge(5)
r = r[ok].sort_values(['rid', 'chulNo']).reset_index(drop=True)
g = pd.factorize(r['rid'])[0]
G = g.max() + 1


def design(feats):
    """경주 안에서 가운데 맞춤·표준화 (빈 값은 경주 평균)"""
    X = np.zeros((len(r), len(feats)))
    for j, f in enumerate(feats):
        v = r[f]
        m = v.groupby(g).transform('mean')
        v = v.fillna(m).fillna(0.0)
        c = v - v.groupby(g).transform('mean')
        if f in BIN:
            X[:, j] = c
        else:
            sd = v.groupby(g).transform('std', ddof=0)
            X[:, j] = np.where(sd > 1e-9, c / sd.replace(0, 1), 0.0)
    return X


y = r['win'].to_numpy(float)
tr_mask = (r['dnum'] < CUT).to_numpy()


def fit_eval(feats):
    X = design(feats)
    gi = g

    def parts(w, mask):
        u = X[mask] @ w
        gg = gi[mask]
        mx = np.full(G, -np.inf); np.maximum.at(mx, gg, u)
        e = np.exp(u - mx[gg])
        s = np.zeros(G); np.add.at(s, gg, e)
        p = e / s[gg]
        return u, gg, p, mx, s

    def nll(w):
        u, gg, p, mx, s = parts(w, tr_mask)
        yy = y[tr_mask]
        ll = (yy * (u - mx[gg] - np.log(s[gg]))).sum()
        grad = ((yy - p)[:, None] * X[tr_mask]).sum(0)
        return -ll + LAM * (w ** 2).sum(), -grad + 2 * LAM * w

    # Newton 법 (scipy 없이): L2 벌점 포함
    w = np.zeros(len(feats))
    Xt = X[tr_mask]; gt = gi[tr_mask]
    for _ in range(50):
        u, gg, p, mx, s = parts(w, tr_mask)
        f, grad = nll(w)
        mxx = np.zeros((G, len(feats))); np.add.at(mxx, gt, p[:, None] * Xt)
        # 정확한 헤시안: Σ_경주 (Σ p x xᵀ − m mᵀ)
        Hs = (p[:, None] * Xt).T @ Xt - mxx.T @ mxx + 2 * LAM * np.eye(len(feats))
        step = np.linalg.solve(Hs, grad)
        w = w - step
        if np.abs(step).max() < 1e-8: break
    te = ~tr_mask
    u, gg, p, mx, s = parts(w, te)
    yy = y[te]
    df = pd.DataFrame({'g': gg, 'u': u, 'p': p, 'win': yy, 'meet': r.loc[te, 'meet'].to_numpy()})
    df['rank'] = df.groupby('g')['u'].rank(ascending=False, method='first')
    wins = df[df['win'] == 1]
    out = {}
    for m, x in [('전체', wins)] + [(NAME[k], wins[wins['meet'] == k]) for k in (1, 2, 3)]:
        out[m] = (len(x), np.log(x['p']).mean(), (x['rank'] == 1).mean(), (x['rank'] <= 5).mean())
    return w, out


print(f'경주 {G:,}개 (학습 ~2025-06 {r.loc[tr_mask, "rid"].nunique():,} / 검증 2025-07~ {r.loc[~tr_mask, "rid"].nunique():,})')
print(f'직전 마체중 있는 비율 {r["prev_bw"].notna().mean():.0%}\n')
w0, a = fit_eval(BASE)
w1, b = fit_eval(BASE + ['prev_bw'])
print(f'{"":>6} {"경주":>6} | {"로그우도 지금":>12} {"+무게":>8} {"차이":>7} | {"1위적중 지금":>11} {"+무게":>7} | {"5두안 지금":>10} {"+무게":>7}')
for k in a:
    n, l0, h0, f0 = a[k]; _, l1, h1, f1 = b[k]
    print(f'{k:>6} {n:>6,} | {l0:>12.4f} {l1:>8.4f} {l1 - l0:>+7.4f} | {h0:>11.1%} {h1:>7.1%} | {f0:>10.1%} {f1:>7.1%}')
print(f'\nprev_bw 가중치 {w1[-1]:+.3f} (표준편차 1만큼 무거우면 점수 +{w1[-1]:.3f})')
n = a['전체'][0]
print(f'검증 경주 전체 로그우도 차이 {(b["전체"][1] - a["전체"][1]) * n:+.1f}  (대략 +3 이상이면 의미 있는 개선)')
