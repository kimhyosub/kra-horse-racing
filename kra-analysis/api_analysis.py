"""API 데이터(서울 2023~2026) 대규모 검증
1) 고정 규칙(사전 등록) 연도별 성적
2) 가설별 시장 대비 실제 우승 (연도별 일관성)
3) 시장+요인 조건부 로짓: 2023H2~2024 학습 → 2025~2026 검증, 가치 베팅 단승/복승 회수율
"""
import math, random, collections, itertools
import numpy as np, pandas as pd
from scipy.optimize import minimize

D = '/home/claude/api/'
r = pd.read_pickle(D + 'features_api.pkl')
r['early4'] = r['early4'].clip(0, 1)
r = r[r['date'] >= '2023-07-01'].copy()                      # 과거전적 워밍업 6개월 제외
r['year'] = r['date'].dt.year
r['period'] = np.where(r['date'] < '2025-01-01', 'train', 'test')
r['rk'] = r['rcDate'] + '-' + r['rcNo'].astype(int).astype(str)
q = pd.read_csv(D + 'q.csv', encoding='utf-8-sig', dtype=str)
q['rk'] = q['rcDate'] + '-' + q['rcNo']
q['a'] = q[['chulNo1', 'chulNo2']].astype(int).min(axis=1); q['b'] = q[['chulNo1', 'chulNo2']].astype(int).max(axis=1)
QODDS = {(k, a, b): float(o) for k, a, b, o in zip(q['rk'], q['a'], q['b'], q['odds'])}

# 경주 내 순위형 파생
g = r.groupby('rk')
r['early_rank'] = g['early4'].rank(method='min')
r['risky'] = r['bwd'] >= 5
r['risky_stable'] = (r['bwd'] >= 5) & r['last_bwd'].between(-4, 4)
r['stable'] = r['bwd'].between(-4, 4) & (r['days'] <= 35)
races = {k: v for k, v in r.groupby('rk')}
YEARS = [2023, 2024, 2025, 2026]

def zstat(sub):
    n = len(sub); w = sub['win'].sum(); e = sub['p_mkt'].sum(); v = (sub['p_mkt'] * (1 - sub['p_mkt'])).sum()
    ret = sub.loc[sub['win'], 'winOdds'].sum()
    return n, w, e, (w - e) / math.sqrt(v) if v > 0 else 0, ret / n * 100 if n else 0

def report(title, groups):
    print(f'\n### {title}')
    hdr = ''.join(f' | {y}: 실제/기대 회수율' for y in YEARS)
    print(f"{'그룹':22s} | 전체: 두수 실제/기대 z 회수율{hdr}")
    for name, sel in groups:
        s = r[sel(r)]
        n, w, e, z, roi = zstat(s)
        line = f"{name:22s} | {n:6d} {w:5d}/{e:6.1f} {z:+5.2f} {roi:4.0f}%"
        for y in YEARS:
            sy = s[s['year'] == y]
            if len(sy): _, wy, ey, zy, ry = zstat(sy); line += f" | {wy:4d}/{ey:6.1f} {ry:4.0f}%"
            else: line += ' |'
        print(line)

print('경주', r['rk'].nunique(), '출주', len(r), '기간', r['date'].min().date(), '~', r['date'].max().date())
print('전 출주마 균등 단승 회수율 %.1f%%' % (r.loc[r['win'], 'winOdds'].sum() / len(r) * 100))

# ---------------- 1) 사전 등록 규칙 ----------------
print('\n### 1. 사전 등록 규칙 (2026-05~10 PDF 데이터로 만든 규칙 → 2023~2026 API 데이터로 검증)')
report('신호', [
    ('위험마(+5kg 이상)', lambda d: d['risky']),
    ('위험마(직전±4→+5)', lambda d: d['risky_stable']),
    ('안정마(±4, 35일내)', lambda d: d['stable']),
    ('그외', lambda d: ~d['risky'] & ~d['stable'])])

def rule_tables():
    rows = []
    for k, d in races.items():
        d = d.sort_values('winOdds'); fav = d.iloc[0]; y = d['year'].iloc[0]
        nos = list(d['chulNo'].astype(int))
        win_no = d.loc[d['win'], 'chulNo'].astype(int).tolist()
        top2 = sorted(d.loc[d['ord'] <= 2, 'chulNo'].astype(int).tolist()[:2])
        qpay = QODDS.get((k, top2[0], top2[1])) if len(top2) == 2 else None
        rec = dict(year=y, rk=k)
        rec['fav_win'] = fav['winOdds'] if fav['win'] else 0
        rec['fav_stable'] = bool(fav['stable']); rec['fav_risky'] = bool(fav['risky'])
        # 복승: 인기 상위3 박스 3장 / 위험마 제외 상위3 박스 3장
        def box(c):
            if qpay is None or len(c) < 3: return None
            pr = [tuple(sorted(x)) for x in itertools.combinations(c[:3], 2)]
            return qpay if tuple(top2) in pr else 0.0
        rec['q_box'] = box(nos)
        rec['q_box_norisky'] = box([int(x) for x, rk in zip(d['chulNo'], d['risky']) if not rk])
        rec['q_fav2'] = (qpay if sorted(nos[:2]) == top2 else 0.0) if qpay is not None else None
        rows.append(rec)
    return pd.DataFrame(rows)

T = rule_tables()
def roi_line(name, sub, col, stake):
    s = sub[sub[col].notna()]
    out = f"{name:30s}"
    for y in YEARS + ['전체']:
        sy = s if y == '전체' else s[s['year'] == y]
        out += f" | {y}: {sy[col].sum() / (len(sy) * stake) * 100 if len(sy) else 0:4.0f}% ({len(sy)})"
    print(out)
roi_line('인기1위 단승 (기준)', T, 'fav_win', 1)
roi_line('A: 인기1위가 안정마일 때만 단승', T[T['fav_stable']], 'fav_win', 1)
roi_line('   인기1위가 위험마일 때 단승', T[T['fav_risky']], 'fav_win', 1)
roi_line('인기1·2위 복승 1장 (기준)', T, 'q_fav2', 1)
roi_line('인기 상위3 복승박스 3장 (기준)', T, 'q_box', 3)
roi_line("C': 위험마 뺀 상위3 복승박스", T, 'q_box_norisky', 3)

# ---------------- 2) 가설 ----------------
o = lambda d: d['winOdds']
report('2-1. 배당 구간', [('~2배', lambda d: o(d) < 2), ('2~4배', lambda d: o(d).between(2, 3.99)), ('4~7배', lambda d: o(d).between(4, 6.99)),
                       ('7~12배', lambda d: o(d).between(7, 11.99)), ('12~25배', lambda d: o(d).between(12, 24.99)),
                       ('25~50배', lambda d: o(d).between(25, 49.99)), ('50배~', lambda d: o(d) >= 50)])
report('2-2. 마체중 증감', [('-10 이하', lambda d: d['bwd'] <= -10), ('-9~-5', lambda d: d['bwd'].between(-9, -5)),
                        ('-4~+4', lambda d: d['bwd'].between(-4, 4)), ('+5~+9', lambda d: d['bwd'].between(5, 9)), ('+10 이상', lambda d: d['bwd'] >= 10)])
report('2-3. 선행력 (최근4전 초반위치 순위)', [('1위', lambda d: d['early_rank'] == 1), ('2위', lambda d: d['early_rank'] == 2),
                                     ('3~4위', lambda d: d['early_rank'].between(3, 4)), ('5위~', lambda d: d['early_rank'] >= 5)])
report('2-4. 출주 간격', [('~21일', lambda d: d['days'] <= 21), ('22~35', lambda d: d['days'].between(22, 35)), ('36~56', lambda d: d['days'].between(36, 56)),
                       ('57~90', lambda d: d['days'].between(57, 90)), ('91~', lambda d: d['days'] > 90), ('첫출전(기록없음)', lambda d: d['n_prev'] == 0)])
report('2-5. 변화 요인', [('장구 변경', lambda d: d['tool_change'] == 1), ('기수 교체', lambda d: d['jk_change'] == 1),
                       ('등급 하락(내려옴)', lambda d: d['cls_change'] > 0), ('등급 상승', lambda d: d['cls_change'] < 0),
                       ('거리 200m+ 단축', lambda d: d['dist_change'] <= -200), ('거리 200m+ 연장', lambda d: d['dist_change'] >= 200),
                       ('부담중량 +2kg↑', lambda d: d['budam_change'] >= 2), ('부담중량 -2kg↓', lambda d: d['budam_change'] <= -2),
                       ('직전 제재', lambda d: d['ref_last'] == 1), ('180일 제재 2회+', lambda d: d['ref_180'] >= 2)])
report('2-6. 기수/조교사 (당시 최근 1년 승률)', [('기수 상위(13%+)', lambda d: d['jk_rate'] >= .13), ('기수 하위(~6%)', lambda d: d['jk_rate'] < .06),
                                         ('조교사 상위(13%+)', lambda d: d['tr_rate'] >= .13), ('조교사 하위(~6%)', lambda d: d['tr_rate'] < .06)])
report('2-7. 젖은 주로(포화/불량)', [('젖은날 선행1위', lambda d: d['wet'] & (d['early_rank'] == 1)), ('마른날 선행1위', lambda d: ~d['wet'] & (d['early_rank'] == 1)),
                                  ('젖은날 젖은주로 입상경험', lambda d: d['wet'] & (d['wet_place'] > 0))])
r.to_pickle(D + 'analysis_frame.pkl')
