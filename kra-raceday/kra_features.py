"""학습(api_features.py)과 동일한 특징값 계산을 '특정 날짜의 출전마'에 대해서만 빠르게 수행하는 모듈.
- prepare_history(): 경주성적 CSV 정제 + 파생값
- features_for(): 출전표(오늘)의 각 말에 대해, 그 날짜 이전 기록만으로 특징값 계산
- score(): 학습된 가중치로 모델확률 계산 (경주 내 표준화 동일)
"""
import collections, math
import numpy as np, pandas as pd

num = lambda s: pd.to_numeric(s, errors='coerce')

FEATS = ['early4', 'g1f4', 'behind3', 'best_behind', 'form3', 'place_rate', 'dist_place', 'log_days', 'first',
         'bwd', 'risky_f', 'stable_f', 'jk_rate', 'tr_rate', 'tool_change_f', 'jk_change', 'cls_change', 'dist_change',
         'ref_last_f', 'ref_180', 'budam_change', 'wgBudam', 'age', 'rating', 'wet_early']
# v5 후보: 마체중 증가 감점을 거리별로 분리 (1300m 이상 / 1200m 이하)
FEATS_V5 = [f for f in FEATS if f != 'risky_f'] + ['risky_long_f', 'risky_short_f']
BIN = {'first', 'risky_f', 'stable_f', 'tool_change_f', 'jk_change', 'ref_last_f', 'risky_long_f', 'risky_short_f'}


def parse_bw(s):
    """'480(+2)' / '480 +2' / '480' → (480, 2 또는 nan)"""
    if s is None or (isinstance(s, float) and math.isnan(s)): return np.nan, np.nan
    import re
    m = re.match(r'^\s*(\d{3})\s*\(?\s*([+-]?\d+)?\s*\)?\s*$', str(s))
    if not m: return np.nan, np.nan
    bw = float(m.group(1)); bw = np.nan if bw == 0 else bw
    return bw, (float(m.group(2)) if m.group(2) is not None else np.nan)


def prepare_history(path):
    """여러 경마장이 섞인 CSV 지원 (경주 키 = 경마장-날짜-경주번호). api_features2.py 와 같은 정의"""
    r = pd.read_csv(path, encoding='utf-8-sig', dtype=str)
    if 'src_meet' not in r.columns: r['src_meet'] = '1'
    r['meetc'] = num(r['src_meet']).fillna(1).astype(int)
    r['rk'] = r['src_meet'] + '-' + r['rcDate'] + '-' + r['rcNo']
    bad = r.groupby('rk')['ordBigo'].transform(lambda s: s.isin(['경주취소', '경주불성립']).any())
    r = r[~bad].copy()
    r = r[~(r['ordBigo'].isin(['출전취소', '경주제외']) | (num(r['winOdds']).fillna(0) <= 0))].copy()
    r['date'] = pd.to_datetime(r['rcDate'], format='%Y%m%d')
    for c in ['rcNo', 'chulNo', 'ord', 'winOdds', 'rcDist', 'rating', 'wgBudam', 'age', 'rcTime',
              'seS1fAccTime', 'seG1fAccTime', 'sjS1fOrd', 'buS1fAccTime', 'buS1fOrd', 'buG1fAccTime', 'jeS1fTime', 'jeG1fTime']:
        r[c] = num(r[c]) if c in r.columns else np.nan
    r['dnf'] = r['ordBigo'].eq('주행중지') | (r['ord'] <= 0)
    r['field'] = r.groupby('rk')['chulNo'].transform('count')
    r.loc[r['dnf'], 'ord'] = r.loc[r['dnf'], 'field']
    r['fin_norm'] = 1 - (r['ord'].clip(upper=r['field']) - 1) / (r['field'] - 1).clip(lower=1)
    r['win'] = (r['ord'] == 1) & ~r['dnf']
    r['top3'] = (r['ord'] <= 3) & ~r['dnf']
    m = r['wgHr'].fillna('').str.extract(r'^(\d+)?\(([+-]?\d+)?\)')
    r['bw'] = num(m[0]); r['bwd'] = num(m[1])
    t = r['track'].fillna('').str.extract(r'^(\S+)\s*\((\d+)%\)')
    r['wet'] = t[0].isin(['포화', '불량'])
    r['cls'] = num(r['rank'].str.extract(r'(\d)등급')[0])
    s1ord = np.where(r['meetc'].isin([3, 4]), r['buS1fOrd'], r['sjS1fOrd'])
    g1f = np.select([r['meetc'] == 1, r['meetc'].isin([3, 4]), r['meetc'] == 2],
                    [r['rcTime'] - r['seG1fAccTime'], r['rcTime'] - r['buG1fAccTime'], r['jeG1fTime']], np.nan)
    acc = np.select([r['meetc'] == 1, r['meetc'].isin([3, 4])], [r['seG1fAccTime'], r['buG1fAccTime']], 1.0)
    r['g1f'] = np.where((r['rcTime'] <= 0) | (acc <= 0) | (g1f <= 0), np.nan, g1f)
    r['early_pos'] = np.where(s1ord > 0, (s1ord - 1) / (r['field'] - 1).clip(lower=1), np.nan)
    r['g1f_rel'] = r['g1f'] - r.groupby('rk')['g1f'].transform('median')
    wt = r[r['win']].groupby('rk')['rcTime'].first()
    r['behind'] = (r['rcTime'] - r['rk'].map(wt)) / r['rcDist'] * 1000
    r.loc[r['rcTime'] <= 0, 'behind'] = np.nan
    return r.sort_values(['date', 'meetc', 'rcNo', 'chulNo']).reset_index(drop=True)


def load_referee(path):
    ref_by = collections.defaultdict(list)
    if not path: return ref_by
    ref = pd.read_csv(path, encoding='utf-8-sig', dtype=str)
    ref = ref[ref['prGubun'] == '경주마']
    for no, d in zip(ref['prNo'], pd.to_datetime(ref['rcDate'], format='%Y%m%d', errors='coerce')):
        ref_by[no].append(d)
    return ref_by


def _rate(hist, key, value, day, meet=1):
    h = hist[(hist['meetc'] == meet) & (hist[key] == value) & (hist['date'] < day) & ((day - hist['date']).dt.days <= 365)]
    return (h['win'].sum() + 0.09 * 20) / (len(h) + 20)


def features_for(entries, hist, ref_by, day, wet_today=None, meet=1):
    """entries: 오늘 출전마 DataFrame (rcNo, chulNo, hrNo, hrName, jkNo, trNo, rcDist, cls, wgBudam, rating, age, hrTool, bw, bwd)
    hist: prepare_history() 결과 (오늘 이전 기록만 사용)"""
    day = pd.Timestamp(day)
    H = hist[hist['date'] < day]
    byhorse = {k: g for k, g in H.groupby('hrNo')}
    rows = []
    for e in entries.itertuples(index=False):
        e = e._asdict()
        f = dict(e)
        P = byhorse.get(e['hrNo'])
        f['n_prev'] = 0 if P is None else len(P)
        refs = [d for d in ref_by.get(e['hrNo'], []) if d < day]
        f['ref_180'] = sum(1 for d in refs if (day - d).days <= 180)
        f['jk_rate'] = _rate(H, 'jkNo', e['jkNo'], day, meet)
        f['tr_rate'] = _rate(H, 'trNo', e['trNo'], day, meet)
        for c in ['win_rate', 'place_rate', 'last_fin', 'form3', 'early4', 'g1f4', 'behind3', 'best_behind', 'days',
                  'last_dist', 'dist_place', 'last_bwd', 'last_budam', 'last_cls', 'jk_change', 'tool_change', 'ref_last']:
            f[c] = np.nan
        if P is not None and len(P):
            L = P.iloc[-1]; n = len(P)
            f['win_rate'] = (P['win'].sum() + 0.4) / (n + 4)
            f['place_rate'] = (P['top3'].sum() + 1.2) / (n + 4)
            f['last_fin'] = L['fin_norm']
            f['form3'] = P['fin_norm'].iloc[-3:].mean()
            f['early4'] = P['early_pos'].iloc[-4:].mean()
            f['g1f4'] = P['g1f_rel'].iloc[-4:].mean()
            f['behind3'] = P['behind'].iloc[-3:].mean()
            f['best_behind'] = P['behind'].iloc[-6:].min()
            f['days'] = (day - L['date']).days
            f['last_dist'] = L['rcDist']
            same = P[(P['rcDist'] - e['rcDist']).abs() <= 100]
            f['dist_place'] = (same['top3'].sum() + 0.3 * 2) / (len(same) + 2)
            f['last_bwd'] = L['bwd']; f['last_budam'] = L['wgBudam']; f['last_cls'] = L['cls']
            f['jk_change'] = float(L['jkNo'] != e['jkNo']) if e.get('jkNo') is not None else np.nan
            if isinstance(e.get('hrTool'), str):
                a = set(e['hrTool'].replace('-', '').split(',')) - {''}
                b = set(str(L['hrTool']).replace('-', '').split(',')) - {''}
                f['tool_change'] = float(a != b)
            f['ref_last'] = float(any(d == L['date'] for d in refs))
        rows.append(f)
    F = pd.DataFrame(rows)
    F['early4'] = F['early4'].clip(0, 1)
    F['dist_change'] = F['rcDist'] - F['last_dist']
    F['budam_change'] = F['wgBudam'] - F['last_budam']
    F['cls_change'] = F['cls'] - F['last_cls']
    F['risky'] = F['bwd'] >= 5
    F['stable'] = F['bwd'].between(-4, 4) & (F['days'] <= 35)
    F['log_days'] = np.log1p(F['days'].fillna(35))
    F['first'] = (F['n_prev'] == 0).astype(float)
    F['risky_f'] = F['risky'].astype(float); F['stable_f'] = F['stable'].astype(float)
    F['risky_long_f'] = (F['risky'] & (F['rcDist'] >= 1300)).astype(float)
    F['risky_short_f'] = (F['risky'] & (F['rcDist'] < 1300)).astype(float)
    F['ref_last_f'] = F['ref_last'].fillna(0); F['tool_change_f'] = F['tool_change'].fillna(0)
    wet = F['wet'] if 'wet' in F.columns else pd.Series(bool(wet_today), index=F.index)
    F['wet_early'] = np.where(wet, -F['early4'].fillna(0.5), 0)
    return F


def race_matrix(d, feats=None):
    feats = feats or FEATS
    X = np.zeros((len(d), len(feats)))
    for j, f in enumerate(feats):
        v = d[f].astype(float).values
        m = np.nanmean(v) if np.any(~np.isnan(v)) else 0.0
        v = np.where(np.isnan(v), m, v)
        sd = v.std()
        X[:, j] = v - v.mean() if (f in BIN or sd < 1e-9) else (v - v.mean()) / sd
    return X


def score(d, w, feats=None):
    """d: 한 경주 출전마 (odds 칼럼 필요). 반환: 시장확률, 모델확률"""
    inv = 1 / d['odds'].values
    pm = inv / inv.sum()
    u = w[0] * np.log(pm) + race_matrix(d, feats) @ np.asarray(w[1:])
    p = np.exp(u - u.max()); p /= p.sum()
    return pm, p
