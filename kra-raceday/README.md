# kra-raceday — 경주 당일 분석 (모의 베팅용)

학습 때와 **똑같은 계산**으로 오늘 출전마의 특징값을 만들고, 학습된 모델로 "모델 확률 × 현재 배당(기대값)"을 계산합니다.
사전 등록 규칙(**기대값 ≥ 1.1, 배당 ≤ 20배 → 단승 1단위**)에 맞는 말을 ★로 표시하고, `paper_log.csv`에 기록합니다. **실제 구매는 하지 않는 모의 기록용**입니다.

## 준비 (최초 1회)
- Python 3.9 이상 설치 → `pip install pandas numpy`
- 수집기(kra-collector)가 `C:\kra-data` 에 `race-result.csv`, `referee.csv`를 만들어 둔 상태
- 이 폴더(kra-raceday)를 원하는 곳에 압축 해제

## 경주일 순서
1. **아침: 데이터 갱신 + 출전표 받기** (IntelliJ 실행 설정의 Program arguments)
   ```
   --kra.from=2026-10-30 --kra.to=2026-11-01 --kra.apis.entry-sheet.enabled=true --kra.output-dir=C:\kra-data
   ```
   → `race-result.csv`(어제까지 결과), `entry-sheet.csv`(오늘 출전표) 갱신
2. **입력 양식 만들기**
   ```
   python raceday.py prepare --date 20261101 --entries C:/kra-data/entry-sheet.csv
   ```
   → `input_20261101.csv` 생성
3. **각 경주 마감 5~10분 전**: 엑셀로 `input_20261101.csv`를 열어 그 경주 칸 채우고 저장 (**"CSV UTF-8" 형식**으로 저장)
   - `odds`: 마사회 앱의 현재 단승 배당
   - `weight`: 발표된 마체중, 예) `480(+2)`
   - `scratch`: 출전취소면 `Y`
   - `track`: 건조/양호/다습/포화/불량
4. **분석 실행**
   ```
   python raceday.py predict --date 20261101 --entries C:/kra-data/entry-sheet.csv --race 5
   ```
   → 말별 시장확률·모델확률·기대값·비고(마체중+, 직전제재, 하위기수 등) 표, 후보 ★ 표시, `paper_log.csv` 기록
5. **다음 날: 정산** (수집기로 결과 갱신 후)
   ```
   python raceday.py settle
   ```
   → 모의 베팅 누적 횟수·적중·회수율

## 빠른 입력 (권장): 경주 하나를 한 줄씩
```
python raceday.py quick --date 20261101 --entries C:/kra-data/entry-sheet.csv --race 5
```
출전마 목록이 마번 순서로 나오면, 앱을 보면서
- `단승 배당` 줄: `3.9 8.4 10.4 7.3 - 10.3 ...` (마번 순서, 취소마는 `-`)
- `마체중 증감` 줄: `+2 -3 0 +5 ...` (모르면 엔터)
- `주로 상태`: 건조/양호/다습/포화/불량
을 입력하면 바로 분석·기록합니다. 엑셀 없이 경주당 30초 정도면 됩니다.

## 점검용
```
python raceday.py replay --date 20261025 --race 4 5 6
```
과거 하루를 당일처럼 재현합니다(최종 배당 사용).

## 경마장 선택 (`--meet`)
모든 명령에 `--meet 1`(서울, 기본) / `--meet 3`(부경) / `--meet 2`(제주)를 붙입니다. 수집기 CSV에 여러 경마장이 섞여 있어도 됩니다.
- 서울: `model_weights.json` (서울 학습)
- 부경: `model_weights_meet3.json` = 서울 가중치 그대로 (부경 자체 학습보다 검증 성적이 좋았음)
- 제주: `model_weights_meet2.json` (제주 학습)
- 영천: 데이터 부족(36경주)으로 전용 모델 없음 → 서울 모델 사용, 참고만

## v5 후보 (그림자 모델)
`model_weights_v5.json`이 있으면 v5 후보도 같이 계산합니다. 마체중 증가 감점을 거리별(1300m 이상 / 1200m 이하)로 나눈 모델입니다.
- 표의 `v5기대` 열, `☆v5` 표시 → **기록만** 하고 공식 규칙(★, v4)은 바뀌지 않습니다.
- `settle`이 v4와 v5의 모의 성적을 따로 보여줍니다. 300회 이상 쌓인 뒤 비교합니다.

## 판단 기준 (미리 정해둔 것)
- 규칙과 가중치는 바꾸지 않고 **300회 이상** 기록
- 누적 회수율이 100%를 안정적으로 넘을 때만 소액 실전 검토
- 기본 경로를 바꾸려면 `--history`, `--referee`, `--log` 옵션 사용
