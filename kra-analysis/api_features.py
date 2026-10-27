"""KRA 경주성적정보 API CSV → 경주 시점 기준 특징값 (미래 정보 누출 없음)
입력: race.csv (RaceDetailResult_1), ref.csv (raceRefereeDetails)
출력: features_api.parquet / .csv
"""
import re, sys, math, collections
import numpy as np, pandas as pd

SRC = sys.argv[1] if len(sys.argv) > 1 else '/home/claude/api/race.csv'
REF = sys.argv[2] if len(sys.argv) > 2 else '/home/claude/api/ref.csv'
OUT = sys.argv[3] if len(sys.argv) > 3 else '/home/claude/api/features_api'

r = pd.read_csv(SRC, encoding='utf-8-sig', dtype=str)
num = lambda s: pd.to_numeric(s, errors='coerce')

# ---------- 경주 정제 ----------
bad_race = r.groupby(['rcDate', 'rcNo'])['ordBigo'].transform(lambda s: s.isin(['경주취소', '경주불성립']).any())
r = r[~bad_race].copy()
r['scr'] = r['ordBigo'].isin(['출전취소', '경주제외']) | (num(r['winOdds']).fillna(0) <= 0)
r = r[~r['scr']].copy()
r['date'] = pd.to_datetime(r['rcDate'], format='%Y%m%d')
for c in ['rcNo', 'chulNo', 'ord', 'winOdds', 'plcOdds', 'rcDist', 'rating', 'wgBudam', 'age', 'rcTime',
          'seS1fAccTime', 'seG1fAccTime', 'sjS1fOrd', 'sjG1fOrd', 'sj_4cOrd']:
    r[c] = num(r[c])
r['dnf'] = r['ordBigo'].eq('주행중지') | (r['ord'] <= 0)
r['field'] = r.groupby(['rcDate', 'rcNo'])['chulNo'].transform('count')
r.loc[r['dnf'], 'ord'] = r.loc[r['dnf'], 'field']
r['fin_norm'] = 1 - (r['ord'].clip(upper=r['field']) - 1) / (r['field'] - 1).clip(lower=1)   # 1착=1, 꼴찌=0
r['win'] = (r['ord'] == 1) & ~r['dnf']
r['top3'] = (r['ord'] <= 3) & ~r['dnf']
inv = 1 / r['winOdds']
r['p_mkt'] = inv / inv.groupby([r['rcDate'], r['rcNo']]).transform('sum')

# 마체중 "499(+11)" / "465()" / "()"
m = r['wgHr'].fillna('').str.extract(r'^(\d+)?\(([+-]?\d+)?\)')
r['bw'] = num(m[0]); r['bwd'] = num(m[1])
# 주로 "다습 (12%)"
t = r['track'].fillna('').str.extract(r'^(\S+)\s*\((\d+)%\)')
r['track_cond'] = t[0]; r['moist'] = num(t[1])
r['wet'] = r['track_cond'].isin(['포화', '불량'])
r['cls'] = num(r['rank'].str.extract(r'(\d)등급')[0])
r['g1f'] = r['rcTime'] - r['seG1fAccTime']                          # 종반 200m
r.loc[(r['rcTime'] <= 0) | (r['seG1fAccTime'] <= 0), 'g1f'] = np.nan
r['s1f'] = r['seS1fAccTime'].where(r['seS1fAccTime'] > 0)
r['early_pos'] = (r['sjS1fOrd'] - 1) / (r['field'] - 1).clip(lower=1)  # 0=선두
r.loc[r['sjS1fOrd'] <= 0, 'early_pos'] = np.nan
# 종반 200m: 같은 경주 중앙값 대비 (음수 = 빠름)
r['g1f_rel'] = r['g1f'] - r.groupby(['rcDate', 'rcNo'])['g1f'].transform('median')
# 우승마 대비 기록차 (1000m 당 초)
wt = r[r['win']].groupby(['rcDate', 'rcNo'])['rcTime'].first()
r['behind'] = (r['rcTime'] - r.set_index(['rcDate', 'rcNo']).index.map(wt)) / r['rcDist'] * 1000
r.loc[r['rcTime'] <= 0, 'behind'] = np.nan
r = r.sort_values(['date', 'rcNo', 'chulNo']).reset_index(drop=True)

# ---------- 심판 제재 ----------
ref = pd.read_csv(REF, encoding='utf-8-sig', dtype=str)
ref = ref[ref['prGubun'] == '경주마'].copy()
ref['date'] = pd.to_datetime(ref['rcDate'], format='%Y%m%d', errors='coerce')
ref_by_horse = collections.defaultdict(list)
for x in ref.itertuples():
    ref_by_horse[x.prNo].append((x.date, x.kind, str(x.reason)))

# ---------- 기수/조교사 최근 365일 성적 (당일 이전만) ----------
def rolling_rate(key):
    out = np.full(len(r), np.nan); hist = collections.defaultdict(collections.deque)
    for d, g in r.groupby('date', sort=True):
        for i, k in zip(g.index, g[key]):
            q = hist[k]
            while q and (d - q[0][0]).days > 365: q.popleft()
            n = len(q); w = sum(x[1] for x in q)
            out[i] = (w + 0.09 * 20) / (n + 20)              # 평균 승률 ~9% 로 축소
        for i, k, wv in zip(g.index, g[key], g['win']):
            hist[k].append((d, int(wv)))
    return out
r['jk_rate'] = rolling_rate('jkNo')
r['tr_rate'] = rolling_rate('trNo')

# ---------- 말별 과거 전적 ----------
F = collections.defaultdict(lambda: np.full(len(r), np.nan))
feat = {}
cols = ['n_prev', 'win_rate', 'place_rate', 'last_fin', 'form3', 'early4', 'g1f4', 'behind3', 'days', 'last_dist',
        'dist_place', 'last_bwd', 'last_budam', 'last_cls', 'jk_change', 'tool_change', 'tool_add', 'tool_rem',
        'wet_place', 'ref_180', 'ref_last', 'last_ord_pop', 'best_behind']
for c in cols: feat[c] = np.full(len(r), np.nan)

for hr, g in r.groupby('hrNo', sort=False):
    idx = list(g.index)                     # 날짜순 정렬됨
    for j, i in enumerate(idx):
        prev = idx[:j]
        P = r.loc[prev] if prev else None
        feat['n_prev'][i] = len(prev)
        d = r.at[i, 'date']
        refs = [x for x in ref_by_horse.get(hr, []) if x[0] < d]
        feat['ref_180'][i] = sum(1 for x in refs if (d - x[0]).days <= 180)
        if not prev:
            continue
        L = r.loc[prev[-1]]
        n = len(prev); w = P['win'].sum(); t3 = P['top3'].sum()
        feat['win_rate'][i] = (w + 0.4) / (n + 4)
        feat['place_rate'][i] = (t3 + 1.2) / (n + 4)
        feat['last_fin'][i] = L['fin_norm']
        feat['form3'][i] = P['fin_norm'].iloc[-3:].mean()
        feat['early4'][i] = P['early_pos'].iloc[-4:].mean()
        feat['g1f4'][i] = P['g1f_rel'].iloc[-4:].mean()
        feat['behind3'][i] = P['behind'].iloc[-3:].mean()
        feat['best_behind'][i] = P['behind'].iloc[-6:].min()
        feat['days'][i] = (d - L['date']).days
        feat['last_dist'][i] = L['rcDist']
        same = P[(P['rcDist'] - r.at[i, 'rcDist']).abs() <= 100]
        feat['dist_place'][i] = (same['top3'].sum() + 0.3 * 2) / (len(same) + 2)
        feat['last_bwd'][i] = L['bwd']
        feat['last_budam'][i] = L['wgBudam']
        feat['last_cls'][i] = L['cls']
        feat['jk_change'][i] = float(L['jkNo'] != r.at[i, 'jkNo'])
        a = set(str(r.at[i, 'hrTool']).replace('-', '').split(',')) - {''}
        b = set(str(L['hrTool']).replace('-', '').split(',')) - {''}
        feat['tool_change'][i] = float(a != b); feat['tool_add'][i] = len(a - b); feat['tool_rem'][i] = len(b - a)
        feat['wet_place'][i] = P[P['wet']]['top3'].sum()
        feat['ref_last'][i] = float(any(x[0] == L['date'] for x in refs))
        # 직전 경주 인기순위
        feat['last_ord_pop'][i] = L['pop'] if 'pop' in r.columns else np.nan
    if False: break

for c in cols: r[c] = feat[c]
r['pop'] = r.groupby(['rcDate', 'rcNo'])['winOdds'].rank(method='first')
r['dist_change'] = r['rcDist'] - r['last_dist']
r['budam_change'] = r['wgBudam'] - r['last_budam']
r['cls_change'] = r['cls'] - r['last_cls']          # + = 하위 등급으로 내려옴
keep = ['rcDate', 'rcNo', 'chulNo', 'hrNo', 'hrName', 'date', 'field', 'ord', 'win', 'top3', 'dnf', 'winOdds', 'plcOdds', 'p_mkt', 'pop',
        'rcDist', 'cls', 'rating', 'wgBudam', 'age', 'sex', 'bw', 'bwd', 'track_cond', 'moist', 'wet', 'weather',
        'jkNo', 'trNo', 'jk_rate', 'tr_rate', 'hrTool'] + cols + ['dist_change', 'budam_change', 'cls_change']
out = r[keep]
out.to_pickle(OUT + '.pkl')
out.to_csv(OUT + '.csv', index=False, encoding='utf-8-sig')
print('rows', len(out), 'races', out.groupby(['rcDate', 'rcNo']).ngroups, 'period', out['date'].min().date(), out['date'].max().date())
print(out[cols + ['bw', 'bwd', 'jk_rate', 'tr_rate']].describe().T[['count', 'mean', 'min', 'max']].round(3))
