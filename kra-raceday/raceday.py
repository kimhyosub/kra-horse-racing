"""경주 당일 분석 도구 (모의 베팅 기록용)

사용 순서
  1) 전날/당일 아침: 수집기로 최신 경주성적(race-result.csv)·심판(referee.csv)·출전표(entry-sheet.csv) 수집
  2) prepare : 출전표로 입력 양식 만들기 → input_YYYYMMDD.csv
  3) 경주 직전: 입력 양식에 그 경주의 '현재 단승 배당', '마체중(예: 480(+2))', 취소마, 주로상태 입력
  4) predict : 모델확률·기대값 계산 → 베팅 후보 출력 + paper_log.csv 에 기록 (실제 구매 X)
  5) 결과 수집 후 settle : 모의 베팅 누적 회수율

예)
  python raceday.py prepare --date 20261101 --entries C:/kra-data/entry-sheet.csv
  python raceday.py predict --date 20261101 --race 5
  python raceday.py quick --date 20261101 --race 5   # 배당·마체중증감을 한 줄씩 입력 → 바로 분석
  python raceday.py settle
  python raceday.py replay --date 20260830      # 과거 하루를 당일처럼 재현(최종배당 사용) - 도구 점검용
"""
import argparse, json, os, sys, math
import numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kra_features as K

HERE = os.path.dirname(os.path.abspath(__file__))
DEF = dict(history='C:/kra-data/race-result.csv', referee='C:/kra-data/referee.csv',
           weights=os.path.join(HERE, 'model_weights.json'), log=os.path.join(HERE, 'paper_log.csv'))
W5_PATH = os.path.join(HERE, 'model_weights_v5.json')   # 그림자 모델(v5 후보): 기록만, 공식 규칙은 v4
W5 = None
MEET = 1   # 1=서울 2=제주 3=부경 4=영천 (--meet)
RULE = dict(ev=1.1, max_odds=20.0)   # 사전 등록 규칙: 모델확률×배당 ≥ 1.1, 배당 ≤ 20 → 단승 1단위


# ---------- 출전표 정규화 ----------
def load_entries(path, date):
    e = pd.read_csv(path, encoding='utf-8-sig', dtype=str)
    dcol = 'rcDate' if 'rcDate' in e.columns else ('src_date' if 'src_date' in e.columns else None)
    if dcol: e = e[e[dcol].astype(str) == date]
    if 'src_meet' in e.columns: e = e[e['src_meet'].astype(str) == str(MEET)]
    if e.empty: sys.exit(f'출전표에 {date} 데이터가 없습니다: {path}')
    need = ['rcNo', 'chulNo', 'hrNo', 'hrName', 'jkNo', 'trNo', 'rcDist', 'wgBudam', 'rating', 'age']
    miss = [c for c in need if c not in e.columns]
    if miss: print(f'[주의] 출전표에 없는 칼럼: {miss} → 해당 요인은 경주 평균값으로 대체됩니다.')
    out = pd.DataFrame({c: e[c] if c in e.columns else np.nan for c in need})
    out['hrTool'] = e['hrTool'] if 'hrTool' in e.columns else np.nan
    out['cls'] = K.num(e['rank'].str.extract(r'(\d)등급')[0]) if 'rank' in e.columns else np.nan
    for c in ['rcNo', 'chulNo', 'rcDist', 'wgBudam', 'rating', 'age']: out[c] = K.num(out[c])
    return out.sort_values(['rcNo', 'chulNo']).reset_index(drop=True)


def entries_from_history(H, date):
    d = H[(H['rcDate'] == date) & (H['meetc'] == MEET)]
    if d.empty: sys.exit(f'경주성적에 {date} 데이터가 없습니다.')
    return d[['rcNo', 'chulNo', 'hrNo', 'hrName', 'jkNo', 'trNo', 'rcDist', 'cls', 'wgBudam', 'rating', 'age', 'hrTool']].copy()


# ---------- 입력 양식 ----------
def cmd_prepare(a):
    E = load_entries(a.entries, a.date)
    t = E[['rcNo', 'chulNo', 'hrName']].rename(columns={'rcNo': 'race', 'chulNo': 'no', 'hrName': 'name'})
    t['odds'] = ''; t['weight'] = ''; t['scratch'] = ''; t['track'] = '건조'
    out = a.input or f'input_{a.date}.csv'
    t.to_csv(out, index=False, encoding='utf-8-sig')
    print(f'입력 양식 생성: {out} ({t["race"].nunique()}경주 {len(t)}두)')
    print('경주 직전에 odds(현재 단승배당), weight(예: 480(+2)), 취소마는 scratch=Y, track(건조/양호/다습/포화/불량) 을 채우세요.')


def read_input(path):
    t = pd.read_csv(path, encoding='utf-8-sig', dtype=str).fillna('')
    t['race'] = K.num(t['race']); t['no'] = K.num(t['no']); t['odds'] = K.num(t['odds'])
    bw = t['weight'].apply(K.parse_bw)
    t['bw'] = [x[0] for x in bw]; t['bwd'] = [x[1] for x in bw]
    t['scratch'] = t['scratch'].str.upper().isin(['Y', '1', 'O', '취소'])
    t['wet'] = t['track'].isin(['포화', '불량'])
    return t


# ---------- 예측 ----------
def predict_day(E, H, ref, date, inp, w, races=None, quiet=False):
    E = E.merge(inp[['race', 'no', 'odds', 'bw', 'bwd', 'scratch', 'wet']], left_on=['rcNo', 'chulNo'], right_on=['race', 'no'], how='left')
    E = E[~E['scratch'].fillna(False).astype(bool)]
    if races: E = E[E['rcNo'].isin(races)]
    E = E[E['odds'].notna() & (E['odds'] > 0)]
    if E.empty:
        if not quiet: print('배당이 입력된 경주가 없습니다. 입력 양식의 odds 칸을 채워주세요.')
        return pd.DataFrame()
    F = K.features_for(E.drop(columns=['race', 'no']), H, ref, pd.to_datetime(date), meet=MEET)
    out = []
    for rn, d in F.groupby('rcNo'):
        d = d.copy()
        pm, p = K.score(d, w)
        d['p_mkt'] = pm; d['p_model'] = p; d['ev'] = p * d['odds']
        d['bet'] = (d['ev'] >= RULE['ev']) & (d['odds'] <= RULE['max_odds'])
        if W5 is not None:
            _, p5 = K.score(d, W5, K.FEATS_V5)
            d['p_v5'] = p5; d['ev_v5'] = p5 * d['odds']
            d['bet_v5'] = (d['ev_v5'] >= RULE['ev']) & (d['odds'] <= RULE['max_odds'])
        else:
            d['p_v5'] = np.nan; d['ev_v5'] = np.nan; d['bet_v5'] = False
        out.append(d)
        if not quiet: print_race(int(rn), d)
    return pd.concat(out)


def note(x):
    s = []
    if x['risky']: s.append('마체중+%d' % x['bwd'])
    elif x['stable']: s.append('안정')
    if x.get('ref_last_f', 0) == 1: s.append('직전제재')
    if x.get('n_prev', 1) == 0: s.append('첫출전')
    if x['jk_rate'] < .06: s.append('하위기수')
    if x['tr_rate'] < .06: s.append('하위조교사')
    if x['odds'] >= 50: s.append('50배↑')
    return ','.join(s)


def print_race(rn, d):
    d = d.sort_values('p_model', ascending=False)
    print(f'\n===== {rn}경주 ({int(d["rcDist"].iloc[0]) if not math.isnan(d["rcDist"].iloc[0]) else "?"}m, {len(d)}두) =====')
    print(f"{'마번':>3} {'마명':<10} {'배당':>6} {'시장%':>6} {'모델%':>6} {'기대값':>6} {'v5기대':>6}  비고")
    for _, x in d.iterrows():
        mark = ('  ★ 후보' if x['bet'] else '') + ('  ☆v5' if x['bet_v5'] else '')
        print(f"{int(x['chulNo']):>3} {str(x['hrName'])[:8]:<10} {x['odds']:6.1f} {x['p_mkt'] * 100:6.1f} {x['p_model'] * 100:6.1f} {x['ev']:6.2f} {x['ev_v5']:6.2f}  {note(x)}{mark}")
    n = int(d['bet'].sum())
    print(f'→ 규칙(기대값≥{RULE["ev"]}, 배당≤{RULE["max_odds"]:.0f}) 후보 {n}두' + ('' if n else ' (이 경주는 패스)'))


def append_log(P, date, path):
    cols = ['meet', 'date', 'race', 'no', 'name', 'odds', 'p_mkt', 'p_model', 'ev', 'bet', 'p_v5', 'ev_v5', 'bet_v5']
    new = pd.DataFrame({'meet': MEET, 'date': date, 'race': P['rcNo'].astype(int), 'no': P['chulNo'].astype(int), 'name': P['hrName'],
                        'odds': P['odds'], 'p_mkt': P['p_mkt'].round(4), 'p_model': P['p_model'].round(4), 'ev': P['ev'].round(3), 'bet': P['bet'],
                        'p_v5': P['p_v5'].round(4), 'ev_v5': P['ev_v5'].round(3), 'bet_v5': P['bet_v5']})
    if os.path.exists(path):
        old = pd.read_csv(path, encoding='utf-8-sig', dtype={'date': str})
        if 'meet' not in old.columns: old['meet'] = 1
        old = old[~(old['meet'].eq(MEET) & old['date'].astype(str).eq(date) & old['race'].isin(new['race'].unique()))]   # 같은 경주는 최신 입력으로 교체
        new = pd.concat([old, new])
    new[cols].to_csv(path, index=False, encoding='utf-8-sig')


def load_common(a):
    if not os.path.exists(a.history): sys.exit(f'경주성적 CSV가 없습니다: {a.history}')
    H = K.prepare_history(a.history)
    ref = K.load_referee(a.referee if os.path.exists(a.referee) else None)
    wpath = a.weights
    if MEET != 1:
        cand = os.path.join(HERE, f'model_weights_meet{MEET}.json')
        if os.path.exists(cand): wpath = cand
        else: print(f'[주의] 경마장 {MEET} 전용 가중치가 없어 서울 모델을 사용합니다 (검증 안 됨).')
    w = json.load(open(wpath, encoding='utf-8'))['w']
    global W5
    if os.path.exists(W5_PATH) and MEET == 1: W5 = json.load(open(W5_PATH, encoding='utf-8'))['w']
    return H, ref, w


def cmd_predict(a):
    H, ref, w = load_common(a)
    E = load_entries(a.entries, a.date) if a.entries else entries_from_history(H, a.date)
    inp = read_input(a.input or f'input_{a.date}.csv')
    P = predict_day(E, H, ref, a.date, inp, w, races=a.race)
    if len(P):
        append_log(P, a.date, a.log)
        print(f'\n모의 베팅 기록 저장: {a.log} (실제 구매 아님)')


def cmd_replay(a):
    """과거 하루를 당일처럼: 출전표=그날 기록, 배당·마체중=최종 확정값"""
    H, ref, w = load_common(a)
    E = entries_from_history(H, a.date)
    d = H[(H['rcDate'] == a.date) & (H['meetc'] == MEET)]
    inp = pd.DataFrame({'race': d['rcNo'], 'no': d['chulNo'], 'odds': d['winOdds'], 'bw': d['bw'], 'bwd': d['bwd'],
                        'scratch': False, 'wet': d['wet']})
    P = predict_day(E, H, ref, a.date, inp, w, races=a.race)
    if len(P):
        R = d.set_index(['rcNo', 'chulNo'])['ord']
        P['ord'] = [R.get((r, c)) for r, c in zip(P['rcNo'], P['chulNo'])]
        b = P[P['bet']]
        print(f'\n[재현 결과] 후보 {len(b)}두, 적중 {int((b["ord"] == 1).sum())}, 회수 {b.loc[b["ord"] == 1, "odds"].sum():.1f} / 투입 {len(b)}')


def _parse_list(txt, n, kind):
    toks = txt.replace(',', ' ').split()
    if len(toks) != n:
        raise ValueError(f'{kind} {len(toks)}개 입력됨, 출전마는 {n}두입니다 (마번 순서대로 모두 입력).')
    return toks


def cmd_quick(a):
    """한 경주를 빠르게 입력: 배당과 마체중 증감을 마번 순서대로 한 줄씩"""
    H, ref, w = load_common(a)
    E = load_entries(a.entries, a.date) if a.entries else entries_from_history(H, a.date)
    path = a.input or f'input_{a.date}.csv'
    if os.path.exists(path):
        t = pd.read_csv(path, encoding='utf-8-sig', dtype=str).fillna('')
    else:
        t = E[['rcNo', 'chulNo', 'hrName']].rename(columns={'rcNo': 'race', 'chulNo': 'no', 'hrName': 'name'}).astype(str)
        t['race'] = t['race'].str.replace(r'\.0$', '', regex=True); t['no'] = t['no'].str.replace(r'\.0$', '', regex=True)
        t['odds'] = ''; t['weight'] = ''; t['scratch'] = ''; t['track'] = '건조'
    for rn in a.race:
        rows = t[t['race'].astype(float) == rn].copy()
        rows = rows.iloc[np.argsort(rows['no'].astype(float).values)]
        if rows.empty: print(f'{rn}경주 출전마가 없습니다.'); continue
        print(f'\n{rn}경주 출전마: ' + '  '.join(f"{n}.{nm}" for n, nm in zip(rows['no'], rows['name'])))
        while True:
            try:
                txt = a.odds if a.odds else input('단승 배당 (마번 순서, 공백 구분, 취소마는 -): ')
                odds = _parse_list(txt, len(rows), '배당'); break
            except ValueError as e:
                print(e)
                if a.odds: sys.exit(1)
        while True:
            try:
                txt = a.bwd if a.bwd is not None else input('마체중 증감 (마번 순서, 예: +2 -3 0 / 모르면 엔터): ')
                bwd = _parse_list(txt, len(rows), '마체중') if txt.strip() else [''] * len(rows); break
            except ValueError as e:
                print(e)
                if a.bwd is not None: sys.exit(1)
        track = a.track or (input('주로 상태 (건조/양호/다습/포화/불량, 엔터=건조): ').strip() or '건조')
        for idx, o, b in zip(rows.index, odds, bwd):
            if o in ('-', '0', 'x', 'X'):
                t.at[idx, 'scratch'] = 'Y'; t.at[idx, 'odds'] = ''
            else:
                t.at[idx, 'scratch'] = ''; t.at[idx, 'odds'] = o
            t.at[idx, 'weight'] = f'000({int(b):+d})' if b not in ('', '?') else ''
            t.at[idx, 'track'] = track
    t.to_csv(path, index=False, encoding='utf-8-sig')
    inp = read_input(path)
    P = predict_day(E, H, ref, a.date, inp, w, races=a.race)
    if len(P):
        append_log(P, a.date, a.log)
        print(f'\n모의 베팅 기록 저장: {a.log} (실제 구매 아님)')


def cmd_settle(a):
    if not os.path.exists(a.log): sys.exit(f'기록 파일이 없습니다: {a.log}')
    L = pd.read_csv(a.log, encoding='utf-8-sig', dtype={'date': str})
    H = K.prepare_history(a.history)
    R = H.set_index(['meetc', 'rcDate', 'rcNo', 'chulNo'])
    if 'meet' not in L.columns: L['meet'] = 1
    def flag(col):
        return L[col].astype(str).str.lower().isin(['true', '1']) if col in L.columns else pd.Series(False, index=L.index)
    for label, col in [('v4 (공식 규칙)', 'bet'), ('v5 후보 (그림자)', 'bet_v5')]:
        rows = []
        for x in L[flag(col)].itertuples():
            key = (int(x.meet), x.date, x.race, x.no)
            if key not in R.index: rows.append((x.date, x.race, x.no, x.name, x.odds, None, None)); continue
            h = R.loc[key]
            rows.append((x.date, x.race, x.no, x.name, x.odds, int(h['ord']), float(h['winOdds'])))
        S = pd.DataFrame(rows, columns=['date', 'race', 'no', 'name', 'odds_at_bet', 'ord', 'final_odds'])
        done = S[S['ord'].notna()]
        print(f'\n===== {label} =====')
        if done.empty: print('정산할 결과가 아직 없습니다.'); continue
        done = done.assign(ret=np.where(done['ord'] == 1, done['final_odds'], 0.0))
        if label.startswith('v4'): print(done.to_string(index=False))
        n = len(done); hit = int((done['ord'] == 1).sum()); ret = done['ret'].sum()
        print(f'모의 베팅 누적: {n}회, 적중 {hit}, 회수율 {ret / n * 100:.1f}%  (미정산 {len(S) - n}건)')
        if n < 300: print(f'※ 판단 기준(300회)까지 {300 - n}회 남음')


def main():
    ap = argparse.ArgumentParser(description='경주 당일 분석 (모의 베팅)')
    sub = ap.add_subparsers(dest='cmd', required=True)
    for name in ['prepare', 'predict', 'replay', 'settle', 'quick']:
        p = sub.add_parser(name)
        p.add_argument('--date', required=name != 'settle')
        p.add_argument('--entries'); p.add_argument('--input')
        p.add_argument('--race', type=int, nargs='*')
        p.add_argument('--history', default=DEF['history']); p.add_argument('--referee', default=DEF['referee'])
        p.add_argument('--weights', default=DEF['weights']); p.add_argument('--log', default=DEF['log'])
        p.add_argument('--odds'); p.add_argument('--bwd'); p.add_argument('--track')
        p.add_argument('--meet', type=int, default=1, help='1=서울 2=제주 3=부경 4=영천')
    a = ap.parse_args()
    global MEET
    MEET = a.meet
    if a.cmd == 'prepare' and not a.entries: sys.exit('--entries (출전표 CSV) 가 필요합니다.')
    dict(prepare=cmd_prepare, predict=cmd_predict, replay=cmd_replay, settle=cmd_settle, quick=cmd_quick)[a.cmd](a)


if __name__ == '__main__':
    main()
