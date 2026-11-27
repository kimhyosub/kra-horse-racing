"""마체중 25kg 구간별 1·2·3착 비율 (경마장별)

  python3 kra-analysis/weight_bins.py /srv/kra-data/race-result.csv [--since 2023-01-01] [--step 25]

- 출전취소·경주취소 제외, 주행중지는 착외로 계산
- '기대 대비'는 실제 우승 수 ÷ (출전 두수로 나눈 평균 우승 기대치). 1.00보다 크면 그 무게대가 평균보다 자주 이김
- 제주는 제주마·한라마라 체구가 작아 따로 본다
"""
import argparse, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'kra-raceday'))
import pandas as pd
import kra_features as K

NAME = {1: '서울', 2: '제주', 3: '부경', 4: '영천'}

ap = argparse.ArgumentParser()
ap.add_argument('csv')
ap.add_argument('--since', default='2023-01-01')
ap.add_argument('--step', type=int, default=25)
a = ap.parse_args()

H = K.prepare_history(a.csv)
H = H[(H['date'] >= pd.Timestamp(a.since)) & (H['bw'] > 0)].copy()
H['place2'] = (H['ord'] == 2) & ~H['dnf']
H['place3'] = (H['ord'] == 3) & ~H['dnf']
H['exp'] = 1.0 / H['field']
H['bin'] = (H['bw'] // a.step * a.step).astype(int)

print(f'기간 {H["date"].min():%Y-%m-%d} ~ {H["date"].max():%Y-%m-%d}, 출주 {len(H):,}건, {a.step}kg 구간\n')
for m, g in H.groupby('meetc'):
    print(f'[{NAME.get(m, m)}]  출주 {len(g):,}  평균 마체중 {g["bw"].mean():.0f}kg')
    print(f'{"마체중":>11} {"출주":>7} {"1착":>6} {"2착":>6} {"3착":>6} {"3착안":>6} {"기대대비":>7}')
    for b, x in g.groupby('bin'):
        if len(x) < 30: continue   # 표본이 너무 적은 구간은 뺀다
        n = len(x)
        print(f'{b:>4}~{b + a.step - 1:<4}kg {n:>7,} {x["win"].mean():>6.1%} {x["place2"].mean():>6.1%} '
              f'{x["place3"].mean():>6.1%} {x["top3"].mean():>6.1%} {x["win"].sum() / x["exp"].sum():>7.2f}')
    print()
