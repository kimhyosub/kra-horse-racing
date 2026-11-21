"""가치마 웹용 경주일 데이터 내보내기

전날 저녁(출전표 발표 후) 실행 → 전날 예상(배당 없는 모델)과 당일 계산에 필요한 요인값을 JSON으로 저장.
경주가 끝난 뒤 다시 실행하면 확정 착순·배당도 함께 들어간다. 웹(kra-web)이 이 파일을 불러온다.

  python export_web.py --date 20261115 --meets 1,4 \
      --entries C:/kra-data/entry-sheet.csv --history C:/kra-data/race-result.csv \
      --referee C:/kra-data/referee.csv --out C:/kra-data/web

  여러 날 한 번에(지난 경주일 채우기): --from 20261106 --to 20261115
  --meets 를 빼면 출전표에 있는 경마장을 전부 내보낸다.
  확정 결과가 있으면 최종 배당·마체중·주로를 'final' 로 넣는다 → 웹 성적 화면이 이 값으로 가치마를 다시 계산한다.
"""
import argparse, json, math, os
import numpy as np, pandas as pd
import kra_features as K
import raceday as RD

HERE = os.path.dirname(os.path.abspath(__file__))
NAME = {1: '서울', 2: '제주', 3: '부경', 4: '영천'}
DAY_W = {1: 'model_weights.json', 2: 'model_weights_meet2.json', 3: 'model_weights_meet3.json', 4: 'model_weights.json'}
DAY_ONLY = ('risky_f', 'stable_f', 'wet_early', 'bwd')   # 당일에 정해지는 값: 웹에서 입력으로 다시 계산
# 전날 예상 근거를 묶는 요인 그룹 (웹 DayModel.GROUPS 와 같은 이름)
GROUPS = {
    '전개(선행력)': ['early4', 'behind3', 'best_behind'],
    '속도 기록': ['g1f4'],
    '최근 폼': ['form3', 'place_rate'],
    '거리 적합': ['dist_place', 'dist_change'],
    '컨디션': ['log_days', 'first'],
    '기수·조교사': ['jk_rate', 'tr_rate', 'jk_change'],
    '조건 변화': ['cls_change', 'tool_change_f', 'budam_change', 'wgBudam', 'age', 'rating'],
    '심판 기록': ['ref_last_f', 'ref_180'],
}


def load_w(name):
    w = json.load(open(os.path.join(HERE, name), encoding='utf-8'))
    return w['w'] if isinstance(w, dict) and 'w' in w else w


def clean(v):
    try:
        v = float(v)
    except (TypeError, ValueError):
        return None
    return None if math.isnan(v) else round(v, 6)


def text(v):
    """출전표의 빈 칸(NaN)은 JSON에 null로"""
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return None
    v = str(v).strip()
    return v or None


def standardize(F, feats, binary):
    X = np.zeros((len(F), len(feats)))
    for j, f in enumerate(feats):
        v = pd.to_numeric(F[f], errors='coerce').astype(float).values
        m = np.nanmean(v) if np.any(~np.isnan(v)) else 0.0
        v = np.where(np.isnan(v), m, v)
        sd = v.std()
        X[:, j] = v - v.mean() if (f in binary or sd < 1e-9) else (v - v.mean()) / sd
    return X


def track_state(s):
    """경주성적의 '양호 (6%)' → 함수율 기준 5단계"""
    s = text(s)
    if not s:
        return None
    m = pd.Series([s]).str.extract(r'\((\d+)%\)')[0].iloc[0]
    if pd.isna(m):
        w = s.split()[0]
        return w if w in ('건조', '양호', '다습', '포화', '불량') else None
    v = int(m)
    return '건조' if v <= 5 else '양호' if v <= 9 else '다습' if v <= 14 else '포화' if v <= 19 else '불량'


def num_or_none(v, nd=None):
    try:
        v = float(v)
    except (TypeError, ValueError):
        return None
    if math.isnan(v):
        return None
    return int(round(v)) if nd == 0 else (round(v, nd) if nd else v)


def raw_rate(H, key, value, day, meet):
    """1년 승률(보정 없는 실제 값)과 출전 수"""
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return None, 0
    h = H[(H['meetc'] == meet) & (H[key] == value) & ((day - H['date']).dt.days <= 365)]
    n = len(h)
    return (round(100 * h['win'].sum() / n, 1) if n else None), n


def facts_for(h, P, H, day, meet):
    """화면에 보여 줄 '근거' 숫자 (사람이 읽는 값)"""
    f = {'starts': 0 if P is None else int(len(P)), 'age': num_or_none(h.get('age'), 0), 'rating': num_or_none(h.get('rating'), 0),
         'budam': num_or_none(h.get('wgBudam'), 1), 'dist': num_or_none(h.get('rcDist'), 0)}
    if P is not None and len(P):
        last = P.iloc[-5:][::-1]   # 최근 것이 앞
        f['recent'] = ['중' if d else str(int(o)) for o, d in zip(last['ord'], last['dnf'])]
        f['wins'] = int(P['win'].sum()); f['top3'] = int(P['top3'].sum())
        f['days'] = int((day - P.iloc[-1]['date']).days)
        f['lastDist'] = num_or_none(P.iloc[-1]['rcDist'], 0)
        same = P[(P['rcDist'] - h['rcDist']).abs() <= 100]
        f['distStarts'] = int(len(same)); f['distTop3'] = int(same['top3'].sum())
        e4 = P['early_pos'].iloc[-4:].mean()
        f['early'] = None if math.isnan(e4) else round(float(e4), 2)            # 0 = 맨 앞, 1 = 맨 뒤
        g = P['g1f_rel'].iloc[-4:].mean()
        f['g1f'] = None if math.isnan(g) else round(float(g), 2)                 # 초, 음수 = 그 경주 중간값보다 빠름
        f['clsChange'] = num_or_none(h.get('cls_change'), 0)
        f['budamChange'] = num_or_none(h.get('budam_change'), 1)
        f['jkChange'] = bool(h.get('jk_change') == 1.0)
        f['toolChange'] = bool(h.get('tool_change_f') == 1.0)
        f['refLast'] = bool(h.get('ref_last_f') == 1.0)
    f['ref180'] = num_or_none(h.get('ref_180'), 0)
    f['jkWin'], f['jkStarts'] = raw_rate(H, 'jkNo', h.get('jkNo'), day, meet)
    f['trWin'], f['trStarts'] = raw_rate(H, 'trNo', h.get('trNo'), day, meet)
    return f


def results_for(R, date, meet):
    r = R[(R['rcDate'] == date) & (R['src_meet'].astype(str) == str(meet))]
    out, jk, final = {}, {}, {}
    for x in r.itertuples():
        rc, no = int(x.rcNo), int(x.chulNo)
        jk[(rc, no)] = x.jkName   # 당일 기수 변경이 반영된 이름
        o = pd.to_numeric(x.ord, errors='coerce')
        if pd.isna(o) or o <= 0:
            continue
        out[(rc, no)] = (int(o), clean(x.winOdds))
        f = final.setdefault(rc, {'track': track_state(x.track), 'odds': {}, 'bwd': {}})
        od = clean(x.winOdds)
        if od is not None and od >= 1:
            f['odds'][str(no)] = od
        m = pd.Series([str(x.wgHr)]).str.extract(r'\(([+-]?\d+)\)')[0].iloc[0]
        if not pd.isna(m):
            f['bwd'][str(no)] = float(m)
    return out, jk, final


POOL_KEYS = {'단승식': '단승', '복승식': '복승', '쌍승식': '쌍승', '삼복승식': '삼복승', '삼쌍승식': '삼쌍승'}


def dividends_for(raw_dir, date, meet, res):
    """확정배당 통합정보(all-odds 원본 JSON)에서 실제 1·2·3착 조합의 확정 배당만 뽑는다 → {경주: {'복승': 12.3, ...}}
    성적 화면의 '당일 분석 1·2·3위 그대로 샀을 때' 승식별 회수율용. 원본이 아직 없으면 빈 dict"""
    path = os.path.join(raw_dir, 'all-odds', f'meet{meet}', date[:4], f'{date}.json')
    if not os.path.exists(path):
        if res:
            print(f'  확정배당 원본 없음: {path} (all-odds 수집 뒤 다시 export 하면 승식 회수율에 들어감)')
        return {}
    try:
        items = json.load(open(path, encoding='utf-8'))
    except ValueError:
        return {}
    order = {}
    for (rc, no), (o, _) in res.items():
        order.setdefault(rc, []).append((o, no))
    win = {}
    for rc, lst in order.items():
        lst.sort()
        ords = [o for o, _ in lst[:3]]
        if len(lst) < 3 or ords != [1, 2, 3]:
            continue   # 동착·실격 등은 건너뜀
        a, b, c = (no for _, no in lst[:3])
        win[rc] = {'단승': (a,), '복승': tuple(sorted((a, b))), '쌍승': (a, b),
                   '삼복승': tuple(sorted((a, b, c))), '삼쌍승': (a, b, c)}
    out = {}
    for x in items if isinstance(items, list) else []:
        k = POOL_KEYS.get(str(x.get('pool', '')).strip())
        if not k:
            continue
        try:
            rc = int(float(x.get('rcNo'))); od = float(x.get('odds'))
            key = tuple(int(float(x.get(c) or 0)) for c in ('chulNo', 'chulNo2', 'chulNo3'))
        except (TypeError, ValueError):
            continue
        key = tuple(v for v in key if v > 0)
        if k in ('복승', '삼복승'):
            key = tuple(sorted(key))
        if rc in win and win[rc][k] == key and od > 0:
            out.setdefault(rc, {})[k] = od
    return out


def export(a):
    pre = json.load(open(os.path.join(HERE, 'model_weights_pre.json'), encoding='utf-8'))
    H = K.prepare_history(a.history)
    ref = K.load_referee(a.referee)
    raw_all = pd.read_csv(a.entries, encoding='utf-8-sig', dtype=str)
    R = pd.read_csv(a.history, encoding='utf-8-sig', dtype=str,
                    usecols=lambda c: c in ('src_meet', 'rcDate', 'rcNo', 'chulNo', 'ord', 'winOdds', 'jkName', 'wgHr', 'track'))
    if a.date:
        dates = [a.date]
    else:
        ds = raw_all['rcDate'].astype(str)
        dates = sorted(d for d in ds.unique() if (not a.date_from or d >= a.date_from) and (not a.date_to or d <= a.date_to))
        if not dates:
            print(f'출전표에 {a.date_from}~{a.date_to} 경주일이 없습니다: {a.entries}')
            return
    for d in dates:
        export_day(a, d, pre, H, ref, raw_all, R)


def export_day(a, date, pre, H, ref, raw_all, R):
    raw = raw_all[raw_all['rcDate'].astype(str) == date]
    meets = [int(x) for x in a.meets.split(',')] if a.meets else sorted({int(x) for x in raw['src_meet'].astype(str)})
    doc = {'date': date, 'feats': K.FEATS, 'bin': sorted(K.BIN), 'dayOnly': list(DAY_ONLY), 'meets': [],
           'pre': {'feats': pre['feats'], 'bin': pre['bin'], 'w': pre['w'], 'note': pre.get('note', '')}}   # 가중치 화면용
    for m in meets:
        RD.MEET = m
        sub = raw[raw['src_meet'].astype(str) == str(m)]
        if sub.empty:
            print(f'{NAME[m]}: {date} 출전표 없음, 건너뜀')
            continue
        E = RD.load_entries(a.entries, date, a.history)
        E['bw'] = np.nan; E['bwd'] = np.nan; E['wet'] = False; E['odds'] = 1.0
        day = pd.to_datetime(date)
        F = K.features_for(E, H, ref, day, meet=m)
        Hb = H[H['date'] < day]
        byhorse = {k: g for k, g in Hb.groupby('hrNo')}
        names = sub.set_index([K.num(sub['rcNo']), K.num(sub['chulNo'])])
        st = sub.groupby(K.num(sub['rcNo']))['stTime'].first()
        res, jk_today, final = results_for(R, date, m)
        divs = dividends_for(a.raw, date, m, res)
        races = []
        for rn, d in F.groupby('rcNo'):
            d = d.sort_values('chulNo').reset_index(drop=True)
            X = standardize(d, pre['feats'], set(pre['bin']))
            W = np.asarray(pre['w'])
            u = X @ W; p = np.exp(u - u.max()); p /= p.sum()
            C = X * W   # 말마다 요인별 기여 (같은 경주 평균 대비)
            col = {f: j for j, f in enumerate(pre['feats'])}
            rank = {i: k + 1 for k, i in enumerate(np.argsort(-p))}
            horses = []
            for i, h in d.iterrows():
                key = (int(rn), int(h['chulNo']))
                nm = names.loc[key] if key in names.index else None
                fin = res.get(key)
                horses.append({
                    'no': int(h['chulNo']), 'hrNo': str(h['hrNo']), 'name': text(h['hrName']),
                    'jkName': text(jk_today.get(key)) or (None if nm is None else text(nm.get('jkName'))),
                    'trName': None if nm is None else text(nm.get('trName')),
                    'days': clean(h['days']), 'first': int(h['first']),
                    'f': [None if f in DAY_ONLY else clean(h[f]) for f in K.FEATS],
                    'preP': round(float(p[i]), 5), 'preRank': int(rank[i]),
                    'ord': None if fin is None else fin[0], 'finalOdds': None if fin is None else fin[1],
                    # 전날 예상에서 요인마다 이 말을 얼마나 올렸나(+)/내렸나(-) — 같은 경주 평균 대비, 로짓 단위
                    'preWhy': {f: round(float(C[i, j]), 4) for f, j in col.items() if abs(C[i, j]) >= 0.005},
                    'facts': facts_for(h, byhorse.get(h['hrNo']), Hb, day, m)})
            t = str(st.get(rn, '')).replace('출발', '').strip().lstrip(':').strip()
            race = {'rcNo': int(rn), 'stTime': t, 'dist': int(d['rcDist'].iloc[0]), 'horses': horses}
            if int(rn) in final and len(final[int(rn)]['odds']) >= 2:
                race['final'] = final[int(rn)]
            if int(rn) in divs:
                race['div'] = divs[int(rn)]   # 실제 착순 조합의 확정 배당 (승식별)
            races.append(race)
        doc['meets'].append({'meet': m, 'name': NAME[m], 'dayWeights': load_w(DAY_W[m]), 'races': races})
        print(f'{NAME[m]}: {len(races)}경주 {sum(len(r["horses"]) for r in races)}두, 확정 착순 {len(res)}두, 승식 배당 {len(divs)}경주')
    if not doc['meets']:
        print(f'{date}: 출전표 없음, 파일을 만들지 않음')
        return
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, f'day-{date}.json')
    tmp = path + '.tmp'   # 웹이 쓰는 도중의 파일을 읽지 않도록 다 쓴 뒤 바꿔치기
    with open(tmp, 'w', encoding='utf-8') as fp:
        json.dump(doc, fp, ensure_ascii=False, allow_nan=False)   # NaN이 섞이면 여기서 바로 오류
    os.replace(tmp, path)
    print('저장', path)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--date', help='하루만: 20261115')
    ap.add_argument('--from', dest='date_from', help='여러 날: 시작일 20261106')
    ap.add_argument('--to', dest='date_to', help='여러 날: 종료일 20261115')
    ap.add_argument('--meets', default=None, help='예: 1,4 (빼면 출전표에 있는 경마장 전부)')
    ap.add_argument('--entries', default='C:/kra-data/entry-sheet.csv')
    ap.add_argument('--history', default='C:/kra-data/race-result.csv')
    ap.add_argument('--referee', default='C:/kra-data/referee.csv')
    ap.add_argument('--raw', default='C:/kra-data/raw', help='수집기 원본 폴더 (all-odds 확정배당)')
    ap.add_argument('--out', default='C:/kra-data/web')
    export(ap.parse_args())
