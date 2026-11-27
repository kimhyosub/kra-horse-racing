"""마체중 효과를 등급·나이·성별 등을 맞춘 상태로 다시 본다 (경마장별)

  python3 kra-analysis/weight_ctrl.py /srv/kra-data/race-result.csv [--since 2023-01-01]

1) 같은 경주 안 비교: 그 경주 출전마 평균보다 몇 kg 무거운가/가벼운가 구간별 1착·3착 안·기대 대비
   → 같은 경주는 등급·거리·주로·날씨가 같으므로 그 차이는 저절로 맞춰진다
2) 조건부 로짓(경주 안에서 누가 이기나): 무게 차이 + 나이 + 성별 + 레이팅 + 부담중량을 함께 넣고
   '다른 조건이 같을 때 10kg 더 무거우면 우승 가능성이 몇 배인가'
3) 같은 모델에 배당(시장 확률)까지 넣었을 때도 무게가 남는가 → 남아야 배당에 비해 쓸모 있는 정보
"""
import argparse, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'kra-raceday'))
import numpy as np, pandas as pd
import kra_features as K

NAME = {1: '서울', 2: '제주', 3: '부경', 4: '영천'}
ap = argparse.ArgumentParser()
ap.add_argument('csv')
ap.add_argument('--since', default='2023-01-01')
a = ap.parse_args()

H = K.prepare_history(a.csv)
H = H[(H['date'] >= pd.Timestamp(a.since)) & (H['bw'] > 0)].copy()
# 한 경주에 우승마가 정확히 하나이고 5두 이상인 경주만
H['nwin'] = H.groupby('rk')['win'].transform('sum')
H['n'] = H.groupby('rk')['bw'].transform('count')
H = H[(H['nwin'] == 1) & (H['n'] >= 5)].copy()

def dev(col):
    x = pd.to_numeric(H[col], errors='coerce') if col in H.columns else pd.Series(np.nan, index=H.index)
    m = x.groupby(H['rk']).transform('mean')
    return (x - m).fillna(0.0)

H['bw_dev'] = H['bw'] - H.groupby('rk')['bw'].transform('mean')
H['age_dev'] = dev('age')
H['rat_dev'] = dev('rating') / 10
H['bud_dev'] = dev('wgBudam')
sex = H['sex'].fillna('') if 'sex' in H.columns else pd.Series('', index=H.index)
H['mare'] = sex.str.contains('암').astype(float)
H['gelding'] = sex.str.contains('거').astype(float)
odds = pd.to_numeric(H['winOdds'], errors='coerce')
q = (1 / odds).where(odds > 0)
H['lmkt'] = np.log((q / q.groupby(H['rk']).transform('sum')).clip(lower=1e-4)).fillna(np.log(1e-4))


def clogit(df, cols, iters=30):
    """경주(rk)마다 softmax로 누가 이기나: Newton 법"""
    X = df[cols].to_numpy(float)
    y = df['win'].to_numpy(float)
    g = pd.factorize(df['rk'])[0]
    G = g.max() + 1
    b = np.zeros(X.shape[1])
    for _ in range(iters):
        u = X @ b
        u = u - pd.Series(u).groupby(g).transform('max').to_numpy()
        e = np.exp(u)
        s = np.zeros(G); np.add.at(s, g, e)
        p = e / s[g]
        mx = np.zeros((G, X.shape[1])); np.add.at(mx, g, p[:, None] * X)
        grad = (y[:, None] * X).sum(0) - (y[:, None] * mx[g]).sum(0)
        xx = np.zeros((G, X.shape[1], X.shape[1])); np.add.at(xx, g, p[:, None, None] * X[:, :, None] * X[:, None, :])
        Hs = -(xx - mx[:, :, None] * mx[:, None, :]).sum(0)
        step = np.linalg.solve(Hs, grad)
        b = b - step
        if np.abs(step).max() < 1e-7: break
    se = np.sqrt(np.diag(np.linalg.inv(-Hs)))
    ll = float(np.log(p[y == 1]).sum())
    return b, se, ll


BINS = [-999, -30, -15, -5, 5, 15, 30, 999]
LAB = ['30kg↓ 가벼움', '15~30 가벼움', '5~15 가벼움', '±5 (평균)', '5~15 무거움', '15~30 무거움', '30kg↑ 무거움']
H['dbin'] = pd.cut(H['bw_dev'], BINS, labels=LAB)
H['exp'] = 1.0 / H['n']

print(f'기간 {H["date"].min():%Y-%m-%d} ~ {H["date"].max():%Y-%m-%d}, 출주 {len(H):,}건, 경주 {H["rk"].nunique():,}개\n')
for m, d in H.groupby('meetc'):
    if d['rk'].nunique() < 300: continue
    print(f'==== [{NAME.get(m, m)}] 경주 {d["rk"].nunique():,}개 ====')
    print('1) 같은 경주 출전마 평균과 비교한 무게')
    print(f'{"":>14} {"출주":>7} {"1착":>6} {"3착안":>6} {"기대대비":>7}')
    for lab, x in d.groupby('dbin', observed=True):
        if len(x) < 50: continue
        print(f'{lab:>14} {len(x):>7,} {x["win"].mean():>6.1%} {x["top3"].mean():>6.1%} {x["win"].sum() / x["exp"].sum():>7.2f}')

    d = d.copy(); d['bw10'] = d['bw_dev'] / 10; d['bw10sq'] = d['bw10'] ** 2
    base = ['age_dev', 'mare', 'gelding', 'rat_dev', 'bud_dev']
    if d['gelding'].sum() < 30: base.remove('gelding')
    if d['mare'].sum() < 30: base.remove('mare')
    if (d['rat_dev'] != 0).mean() < 0.3: base.remove('rat_dev')   # 레이팅이 거의 없는 경마장
    base = [c for c in base if c in d and d[c].std() > 1e-9]       # 값이 다 같은 항목은 뺀다
    print('\n2) 나이·성별·레이팅·부담중량을 맞춘 뒤 (조건부 로짓)')
    b, se, ll1 = clogit(d, ['bw10', 'bw10sq'] + base)
    _, _, ll0 = clogit(d, base)
    print(f'   같은 조건에서 10kg 더 무거우면 우승 가능성 ×{np.exp(b[0]):.2f}  (95% 범위 ×{np.exp(b[0] - 2 * se[0]):.2f}~×{np.exp(b[0] + 2 * se[0]):.2f})')
    print(f'   곡선(제곱항) {b[1]:+.3f} ± {se[1]:.3f}  ' + ('→ 너무 무거우면 다시 불리' if b[1] < -2 * se[1] else '→ 뚜렷한 꺾임 없음'))
    print(f'   무게를 넣어 좋아진 정도(로그우도) {ll1 - ll0:+.1f}  ' + ('(의미 있음)' if ll1 - ll0 > 6 else '(작음)'))
    for c, bb, ss in zip(['bw10', 'bw10sq'] + base, b, se):
        if c in ('bw10', 'bw10sq'): continue
        print(f'     참고 {c:>8}: {bb:+.3f} ± {ss:.3f}')

    print('\n3) 배당(시장 확률)까지 넣었을 때 무게가 남는가')
    b2, se2, ll2 = clogit(d, ['lmkt', 'bw10', 'bw10sq'] + base)
    _, _, ll3 = clogit(d, ['lmkt'] + base)
    print(f'   10kg 더 무거우면 ×{np.exp(b2[1]):.2f}  (95% 범위 ×{np.exp(b2[1] - 2 * se2[1]):.2f}~×{np.exp(b2[1] + 2 * se2[1]):.2f}),'
          f' 좋아진 정도 {ll2 - ll3:+.1f} ' + ('→ 배당에 아직 덜 반영된 정보' if ll2 - ll3 > 6 and abs(b2[1]) > 2 * se2[1] else '→ 배당이 이미 대부분 반영'))
    print()
