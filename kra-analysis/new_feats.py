"""픽마 요소에서 착안한 새 요인 5개 시험 (경주 시점 정보만 사용)
1 jt_combo   기수×조교사 조합 365일 승률(축소추정)
2 instab     최근 5경주 착순(정규화) 표준편차 = 불안정성
3 opp_level  최근 4경주 상대 수준 (그 경주 출전마들의 평균 시장확률 기준 '강한 판' 여부 → 경주 내 상대 레이팅 평균)
4 front_comp 선행형(early4<0.3)인데 이번 경주 다른 선행형 수 (선행 경합)
5 s1f4       최근 4경주 초반 200m 상대기록 (경주 중앙값 대비, 작을수록 빠름)
"""
import sys, json, random, collections
import numpy as np, pandas as pd
sys.path.insert(0, '/home/claude/tools')
import api_model as M
r = pd.read_pickle('/home/claude/api2/feat_all.pkl').sort_values(['date', 'meetc', 'rcNo', 'chulNo']).reset_index(drop=True)
num = lambda s: pd.to_numeric(s, errors='coerce')

# --- 1 기수×조교사 조합 (경마장별, 365일, 당일 이전만)
out = np.full(len(r), np.nan); hist = collections.defaultdict(collections.deque)
keys = r['src_meet'] + '|' + r['jkNo'].astype(str) + '|' + r['trNo'].astype(str)
for d, g in r.groupby('date', sort=True):
    ks = keys[g.index]
    for i, k in zip(g.index, ks):
        q = hist[k]
        while q and (d - q[0][0]).days > 365: q.popleft()
        out[i] = (sum(x[1] for x in q) + 0.09 * 10) / (len(q) + 10)
    for k, wv in zip(ks, g['win']): hist[k].append((d, int(wv)))
r['jt_combo'] = out

# --- 5 초반 200m 상대기록 (경마장별 필드)
s1 = np.select([r['meetc'] == 1, r['meetc'].isin([3, 4]), r['meetc'] == 2],
               [num(r['seS1fAccTime']), num(r['buS1fAccTime']), num(r['jeS1fTime'])], np.nan)
s1 = np.where(s1 > 0, s1, np.nan); r['s1f'] = s1
r['s1f_rel'] = r['s1f'] - r.groupby('rk')['s1f'].transform('median')

# --- 3 그 경주 상대 수준: 출전마 레이팅 평균(0 제외) 경주 내 → 경마장·연도별 표준화
rt = num(r['rating']).where(lambda x: x > 0)
r['field_rating'] = rt.groupby(r['rk']).transform('mean')

# 말별 과거 기록에서 시점 특징 (shift로 오늘 경주 제외)
g = r.groupby('hrNo', sort=False)
r['instab'] = g['fin_norm'].transform(lambda s: s.shift(1).rolling(5, min_periods=3).std())
r['opp_level'] = g['field_rating'].transform(lambda s: s.shift(1).rolling(4, min_periods=1).mean())
r['s1f4'] = g['s1f_rel'].transform(lambda s: s.shift(1).rolling(4, min_periods=1).mean())

# --- 4 선행 경합
fr = (r['early4'] < 0.3).astype(float)
r['front_comp'] = fr * (fr.groupby(r['rk']).transform('sum') - fr)
r.to_pickle('/home/claude/api2/feat_all_v6.pkl')
print('coverage', {c: round(r[c].notna().mean(), 2) for c in ['jt_combo', 'instab', 'opp_level', 'front_comp', 's1f4']})
