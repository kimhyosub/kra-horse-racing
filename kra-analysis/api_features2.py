"""다경마장(서울·제주·부경·영천) 경주 시점 특징값 — api_features.py 와 같은 정의, 경마장 구분 + 속도 개선
경마장별 구간기록 필드 차이:
  서울: seS1fAccTime / sjS1fOrd / seG1fAccTime(누적)   부경·영천: buS1fAccTime / buS1fOrd / buG1fAccTime(누적)
  제주: jeS1fTime / sjS1fOrd / jeG1fTime(구간)
"""
import sys, collections
import numpy as np, pandas as pd

SRC, REF, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
num = lambda s: pd.to_numeric(s, errors='coerce')
r = pd.read_csv(SRC, encoding='utf-8-sig', dtype=str)
r['meetc'] = r['src_meet'].astype(int)
r['rk'] = r['src_meet'] + '-' + r['rcDate'] + '-' + r['rcNo']
bad = r.groupby('rk')['ordBigo'].transform(lambda s: s.isin(['경주취소', '경주불성립']).any())
r = r[~bad]
r = r[~(r['ordBigo'].isin(['출전취소', '경주제외']) | (num(r['winOdds']).fillna(0) <= 0))].copy()
r['date'] = pd.to_datetime(r['rcDate'], format='%Y%m%d')
for c in ['rcNo', 'chulNo', 'ord', 'winOdds', 'plcOdds', 'rcDist', 'rating', 'wgBudam', 'age', 'rcTime',
          'seS1fAccTime', 'seG1fAccTime', 'sjS1fOrd', 'buS1fAccTime', 'buS1fOrd', 'buG1fAccTime', 'jeS1fTime', 'jeG1fTime']:
    r[c] = num(r[c])
r['dnf'] = r['ordBigo'].eq('주행중지') | (r['ord'] <= 0)
r['field'] = r.groupby('rk')['chulNo'].transform('count')
r.loc[r['dnf'], 'ord'] = r.loc[r['dnf'], 'field']
r['fin_norm'] = 1 - (r['ord'].clip(upper=r['field']) - 1) / (r['field'] - 1).clip(lower=1)
r['win'] = (r['ord'] == 1) & ~r['dnf']; r['top3'] = (r['ord'] <= 3) & ~r['dnf']
inv = 1 / r['winOdds']; r['p_mkt'] = inv / inv.groupby(r['rk']).transform('sum')
m = r['wgHr'].fillna('').str.extract(r'^(\d+)?\(([+-]?\d+)?\)'); r['bw'] = num(m[0]); r['bwd'] = num(m[1])
t = r['track'].fillna('').str.extract(r'^(\S+)\s*\((\d+)%\)'); r['track_cond'] = t[0]; r['moist'] = num(t[1])
r['wet'] = r['track_cond'].isin(['포화', '불량'])
r['cls'] = num(r['rank'].str.extract(r'(\d)등급')[0])
# 경마장별 구간
s1ord = np.where(r['meetc'].isin([3, 4]), r['buS1fOrd'], r['sjS1fOrd'])
g1f = np.select([r['meetc'] == 1, r['meetc'].isin([3, 4]), r['meetc'] == 2],
                [r['rcTime'] - r['seG1fAccTime'], r['rcTime'] - r['buG1fAccTime'], r['jeG1fTime']], np.nan)
acc = np.select([r['meetc'] == 1, r['meetc'].isin([3, 4])], [r['seG1fAccTime'], r['buG1fAccTime']], 1.0)
g1f = np.where((r['rcTime'] <= 0) | (acc <= 0) | (g1f <= 0), np.nan, g1f)
r['g1f'] = g1f
r['early_pos'] = np.where(s1ord > 0, (s1ord - 1) / (r['field'] - 1).clip(lower=1), np.nan)
r['g1f_rel'] = r['g1f'] - r.groupby('rk')['g1f'].transform('median')
wt = r[r['win']].groupby('rk')['rcTime'].first()
r['behind'] = (r['rcTime'] - r['rk'].map(wt)) / r['rcDist'] * 1000
r.loc[r['rcTime'] <= 0, 'behind'] = np.nan
r = r.sort_values(['date', 'meetc', 'rcNo', 'chulNo']).reset_index(drop=True)

ref = pd.read_csv(REF, encoding='utf-8-sig', dtype=str); ref = ref[ref['prGubun'] == '경주마']
ref_by = collections.defaultdict(list)
for no, d in zip(ref['prNo'], pd.to_datetime(ref['rcDate'], format='%Y%m%d', errors='coerce')): ref_by[no].append(d)

def rolling_rate(key):
    out = np.full(len(r), np.nan); hist = collections.defaultdict(collections.deque)
    keys = r['src_meet'] + '|' + r[key].astype(str)
    for d, g in r.groupby('date', sort=True):
        ks = keys[g.index]
        for i, k in zip(g.index, ks):
            q = hist[k]
            while q and (d - q[0][0]).days > 365: q.popleft()
            out[i] = (sum(x[1] for x in q) + 0.09 * 20) / (len(q) + 20)
        for k, wv in zip(ks, g['win']): hist[k].append((d, int(wv)))
    return out
r['jk_rate'] = rolling_rate('jkNo'); r['tr_rate'] = rolling_rate('trNo')

cols = ['n_prev', 'win_rate', 'place_rate', 'last_fin', 'form3', 'early4', 'g1f4', 'behind3', 'best_behind', 'days',
        'last_dist', 'dist_place', 'last_bwd', 'last_budam', 'last_cls', 'jk_change', 'tool_change', 'wet_place', 'ref_180', 'ref_last']
F = {c: np.full(len(r), np.nan) for c in cols}
A = {c: r[c].values for c in ['date', 'win', 'top3', 'fin_norm', 'early_pos', 'g1f_rel', 'behind', 'rcDist', 'bwd', 'wgBudam', 'cls', 'jkNo', 'hrTool', 'wet']}
for hr, idx in r.groupby('hrNo').indices.items():
    idx = np.sort(idx)
    refs = sorted(ref_by.get(hr, []))
    for j, i in enumerate(idx):
        d = A['date'][i]
        F['n_prev'][i] = j
        F['ref_180'][i] = sum(1 for x in refs if x < d and (d - x).days <= 180)
        if j == 0: continue
        P = idx[:j]; L = idx[j - 1]
        n = j
        F['win_rate'][i] = (A['win'][P].sum() + .4) / (n + 4)
        F['place_rate'][i] = (A['top3'][P].sum() + 1.2) / (n + 4)
        F['last_fin'][i] = A['fin_norm'][L]
        F['form3'][i] = np.nanmean(A['fin_norm'][P[-3:]])
        with np.errstate(all='ignore'):
            F['early4'][i] = np.nanmean(A['early_pos'][P[-4:]]) if np.any(~np.isnan(A['early_pos'][P[-4:]])) else np.nan
            F['g1f4'][i] = np.nanmean(A['g1f_rel'][P[-4:]]) if np.any(~np.isnan(A['g1f_rel'][P[-4:]])) else np.nan
            F['behind3'][i] = np.nanmean(A['behind'][P[-3:]]) if np.any(~np.isnan(A['behind'][P[-3:]])) else np.nan
            F['best_behind'][i] = np.nanmin(A['behind'][P[-6:]]) if np.any(~np.isnan(A['behind'][P[-6:]])) else np.nan
        F['days'][i] = (d - A['date'][L]).astype('timedelta64[D]').astype(int)
        F['last_dist'][i] = A['rcDist'][L]
        same = P[np.abs(A['rcDist'][P] - A['rcDist'][i]) <= 100]
        F['dist_place'][i] = (A['top3'][same].sum() + .6) / (len(same) + 2)
        F['last_bwd'][i] = A['bwd'][L]; F['last_budam'][i] = A['wgBudam'][L]; F['last_cls'][i] = A['cls'][L]
        F['jk_change'][i] = float(A['jkNo'][L] != A['jkNo'][i])
        a = set(str(A['hrTool'][i]).replace('-', '').split(',')) - {''}; b = set(str(A['hrTool'][L]).replace('-', '').split(',')) - {''}
        F['tool_change'][i] = float(a != b)
        F['wet_place'][i] = A['top3'][P][A['wet'][P].astype(bool)].sum()
        F['ref_last'][i] = float(any(x == A['date'][L] for x in refs))
for c in cols: r[c] = F[c]
r['early4'] = r['early4'].clip(0, 1)
r['dist_change'] = r['rcDist'] - r['last_dist']; r['budam_change'] = r['wgBudam'] - r['last_budam']; r['cls_change'] = r['cls'] - r['last_cls']
r.to_pickle(OUT)
print('rows', len(r), 'races', r['rk'].nunique()); print(r.groupby('meetc').agg(races=('rk', 'nunique'), rows=('rk', 'size')))
