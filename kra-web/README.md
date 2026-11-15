# kra-web — 가치마

전날 저녁에 1~5위를 예상해 두고, 경주 당일 배당·마체중을 넣으면 다시 계산해 보여주는 웹.

```
전날 저녁   export_web.py → day-YYYYMMDD.json → [관리] 폴더에서 불러오기 → 전날 예상 화면
경주 당일   경주 화면에서 배당·마체중·주로 입력 → 당일 모델로 다시 계산 (전날 순위 대비 ▲▼, 가치마 ★)
경주 후     수집기로 결과 받기 → export_web.py 다시 실행 → 불러오기 → 확정 착순 표시
```

## 실행

1. Java 8 이상, Maven (IntelliJ에서 `kra-web/pom.xml`을 Maven 프로젝트로 열기)
2. `GachimaApplication` 실행 → http://localhost:8090
   - DB: `kra-web/data/gachima.mv.db` (H2 파일, 자동 생성)
   - 불러올 폴더: 환경변수 `GACHIMA_IMPORT_DIR` (기본 `C:/kra-data/web`)

## 전날 저녁 순서

1. 수집기로 다음 날 출전표 받기 (날짜는 `yyyy-MM-dd` 형식)
   ```
   --kra.from=2026-11-20 --kra.to=2026-11-21 --kra.meets=2,3 --kra.apis.entry-sheet.enabled=true --kra.apis.quinella-odds.enabled=false --kra.output-dir=C:\kra-data
   ```
2. 웹용 파일 만들기 (kra-raceday 폴더에서)
   ```
   python export_web.py --date 20261120 --meets 2,3 --out C:/kra-data/web
   ```
3. http://localhost:8090/admin → **폴더에서 불러오기**

## 경주 당일

경주 화면 → **당일 분석** → **배당·마체중 입력** → 말마다 단승 배당과 마체중 증감(예: `+3`, `-5`), 주로 상태 → **다시 계산**.
출전 취소는 배당 칸을 비우면 됩니다. 입력은 경주마다 쌓이고 가장 최근 입력으로 계산합니다.

## 계산

- 전날 예상: `kra-raceday/model_weights_pre.json` (배당 없이 21개 요인, `kra-analysis/pre_model.py`로 학습).
  2025-07 이후 3,201경주 검증: 1위 적중 29.9%, 상위 5두 안에 우승마 80.1%.
- 당일 분석: 경마장별 당일 모델 가중치(`model_weights*.json`)를 `DayModel.java`가 Python `kra_features.score()`와 같은 식으로 계산.
  `DayModelTest`가 2026-11-15 서울 7R로 두 계산이 같은지 확인합니다.
- 가치마: 모델 확률 × 배당 ≥ 1.1 이고 배당 ≤ 20배.

## 아직 없는 것 (다음 단계)

- 로그인·관리 화면 보호 (지금은 누구나 입력 가능 → 공개 서버에 올리기 전에 필수)
- 스크린샷 업로드로 배당 자동 읽기
- 검증 리포트 (전날 예상·당일 분석·실제 착순 누적 비교)
- 정보 제공용 서비스이며 마권 구매 기능은 넣지 않습니다.
