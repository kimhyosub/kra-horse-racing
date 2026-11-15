"""가치마 웹용 경주일 데이터 내보내기

전날 저녁(출전표 발표 후) 실행 → 전날 예상(배당 없는 모델)과 당일 계산에 필요한 요인값을 JSON으로 저장.
경주가 끝난 뒤 다시 실행하면 확정 착순·배당도 함께 들어간다. 웹(kra-web)이 이 파일을 불러온다.

  python export_web.py --date 20261115 --meets 1,4 \
      --entries C:/kra-data/entry-sheet.csv --history C:/kra-data/race-result.csv \
      --referee C:/kra-data/referee.csv --out C:/kra-data/web
"""
import argparse, json, math, os
import numpy as np, pandas as pd
import kra_features as K
import raceday as RD

HERE = os.path.dirname(os.path.abspath(__file__))
NAME = {1: '서울', 2: '제주', 3: '부경', 4: '영천'}
DAY_W = {1: 'model_weights.json', 2: 'model_weights_meet2.json', 3: 'model_weights_meet3.json', 4: 'model_weights.json'}
DAY_ONLY = ('risky_f', 'stable_f', 'wet_early', 'bwd')   # 당일에 정해지는 값: 웹에서 입력으로 다시 계산


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


def results_for(hist_path, date, meet):
    r = pd.read_csv(hist_path, encoding='utf-8-sig', dtype=str,
                    usecols=lambda c: c in ('src_meet', 'rcDate', 'rcNo', 'chulNo', 'ord', 'winOdds', 'jkName'))
    r = r[(r['rcDate'] == date) & (r['src_meet'].astype(str) == str(meet))]
    out, jk = {}, {}
    for x in r.itertuples():
        key = (int(x.rcNo), int(x.chulNo))
        jk[key] = x.jkName   # 당일 기수 변경이 반영된 이름
        o = pd.to_numeric(x.ord, errors='coerce')
        if pd.isna(o) or o <= 0:
            continue
        out[key] = (int(o), clean(x.winOdds))
    return out, jk


def export(a):
    pre = json.load(open(os.path.join(HERE, 'model_weights_pre.json'), encoding='utf-8'))
    H = K.prepare_history(a.history)
    ref = K.load_referee(a.referee)
    raw = pd.read_csv(a.entries, encoding='utf-8-sig', dtype=str)
    raw = raw[raw['rcDate'].astype(str) == a.date]
    doc = {'date': a.date, 'feats': K.FEATS, 'bin': sorted(K.BIN), 'dayOnly': list(DAY_ONLY), 'meets': []}
    for m in [int(x) for x in a.meets.split(',')]:
        RD.MEET = m
        sub = raw[raw['src_meet'].astype(str) == str(m)]
        if sub.empty:
            print(f'{NAME[m]}: {a.date} 출전표 없음, 건너뜀')
            continue
        E = RD.load_entries(a.entries, a.date, a.history)
        E['bw'] = np.nan; E['bwd'] = np.nan; E['wet'] = False; E['odds'] = 1.0
        F = K.features_for(E, H, ref, pd.to_datetime(a.date), meet=m)
        names = sub.set_index([K.num(sub['rcNo']), K.num(sub['chulNo'])])
        st = sub.groupby(K.num(sub['rcNo']))['stTime'].first()
        res, jk_today = results_for(a.history, a.date, m)
        races = []
        for rn, d in F.groupby('rcNo'):
            d = d.sort_values('chulNo').reset_index(drop=True)
            X = standardize(d, pre['feats'], set(pre['bin']))
            u = X @ np.asarray(pre['w']); p = np.exp(u - u.max()); p /= p.sum()
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
                    'ord': None if fin is None else fin[0], 'finalOdds': None if fin is None else fin[1]})
            t = str(st.get(rn, '')).replace('출발', '').strip().lstrip(':').strip()
            races.append({'rcNo': int(rn), 'stTime': t, 'dist': int(d['rcDist'].iloc[0]), 'horses': horses})
        doc['meets'].append({'meet': m, 'name': NAME[m], 'dayWeights': load_w(DAY_W[m]), 'races': races})
        print(f'{NAME[m]}: {len(races)}경주 {sum(len(r["horses"]) for r in races)}두, 확정 착순 {len(res)}두')
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, f'day-{a.date}.json')
    json.dump(doc, open(path, 'w', encoding='utf-8'), ensure_ascii=False, allow_nan=False)   # NaN이 섞이면 여기서 바로 오류
    print('저장', path)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--date', required=True)
    ap.add_argument('--meets', default='1')
    ap.add_argument('--entries', default='C:/kra-data/entry-sheet.csv')
    ap.add_argument('--history', default='C:/kra-data/race-result.csv')
    ap.add_argument('--referee', default='C:/kra-data/referee.csv')
    ap.add_argument('--out', default='C:/kra-data/web')
    export(ap.parse_args())
