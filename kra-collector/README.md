# kra-collector

한국마사회 공공데이터 API(공공데이터포털)를 날짜 범위로 수집해서 원본 JSON과 CSV로 저장하는 Spring Boot 배치입니다.

- Java 8 / Spring Boot 2.7.18 / 웹서버 없이 실행 후 종료
- 날짜 × 경마장 × API 단위로 원본 저장, 재실행 시 받은 날짜는 건너뜀(이어받기)
- 하루 호출 한도(기본 2,800) 도달 시 깔끔하게 중단 → 다음 날 같은 명령으로 이어서 수집
- 응답 필드를 고정하지 않고 받은 그대로 CSV 칼럼으로 펼침 (필드명이 바뀌어도 동작)

## 1. 준비

1. [공공데이터포털](https://www.data.go.kr)에서 아래 API 활용신청 → 마이페이지에서 **일반 인증키** 확인
   - 한국마사회_경주성적정보 (15063979) — 필수
   - 한국마사회 복승식 확정배당율 (15057090) — 권장
   - 한국마사회 경주 심판 정보 (15036402) — 권장
2. 각 API 페이지의 Swagger/참고문서에서 **요청 URL과 날짜 파라미터 이름**을 확인해 `application.yml`의 `kra.apis`에 맞춥니다.
   - 경주성적정보는 `https://apis.data.go.kr/B551015/API214_1/RaceDetailResult_1`, `rc_date=yyyyMMdd`로 넣어두었습니다. 다르면 고쳐주세요.
   - 복승·심판 API는 URL을 확인한 뒤 `enabled: true`로 바꾸면 됩니다.

## 2. 빌드

```bash
mvn clean package
```

## 3. 실행

```bash
# 키는 환경변수 또는 인자로
export KRA_SERVICE_KEY='발급받은_일반인증키'

# 서울(1) 2024-01-01 ~ 2026-10-25 수집 후 CSV 내보내기
java -jar target/kra-collector-1.0.0.jar \
  --kra.from=2024-01-01 --kra.to=2026-10-25 --kra.meets=1

# 수집만 / CSV만
java -jar target/kra-collector-1.0.0.jar --kra.mode=collect
java -jar target/kra-collector-1.0.0.jar --kra.mode=export
```

윈도우 PowerShell: `$env:KRA_SERVICE_KEY="..."` 후 같은 명령.

### 0) 엔드포인트 자동 확인 (처음 한 번)

```bash
java -jar target/kra-collector-1.0.0.jar --kra.mode=probe
```

`application.yml`의 `probe-urls` 후보를 차례로 호출해서 데이터가 나오는 URL과 응답 필드 목록을 출력합니다. 나온 URL을 `kra.apis.race-result.url`에 넣으세요.

### 먼저 하루만 시험해 보세요

```bash
java -jar target/kra-collector-1.0.0.jar --kra.from=2026-08-30 --kra.to=2026-08-30
```

`kra-data/raw/race-result/meet1/2026/20260830.json`에 10경주 약 100두가 들어오면 정상입니다.
비어 있으면 URL이나 날짜 파라미터 이름(`date-param`)을 확인하세요. 비어 있는 날도 `[]`로 저장되므로, 설정을 고친 뒤에는 해당 파일을 지우고 다시 실행하세요.

## 4. 결과물

```
kra-data/
├── raw/race-result/meet1/2026/20260830.json   # 원본 (날짜별)
├── race-result.csv                             # 전체 CSV (UTF-8 BOM, 엑셀에서 바로 열림)
├── quinella-odds.csv
└── referee.csv
```

CSV 앞의 `src_meet`, `src_date`는 수집기가 붙인 칼럼(경마장, 조회 날짜)이고, 나머지는 API 응답 필드 그대로입니다.

## 5. 호출량

- 날짜 1일 × 경마장 1곳 × API 1개 = 보통 1회 호출 (1,000행 넘으면 페이지 추가)
- `weekdays: []`이면 매일 조회합니다. 경마가 없는 날도 1회씩 호출하지만 공휴일 경마를 놓치지 않습니다.
- 서울 1년 × API 3개 ≈ 1,100회 → 개발계정(일 3,000회)으로 하루 2~3년치
- 호출을 줄이려면 `weekdays: [FRIDAY, SATURDAY, SUNDAY]`

## 6. 오류 코드

| 코드 | 의미 | 동작 |
|---|---|---|
| 03 | 데이터 없음 | 빈 날로 저장 |
| 22 | 일일 호출 한도 초과 | 중단 (다음 날 이어받기) |
| 23 | 초당 호출 초과 | 잠시 후 재시도 (`request-delay-ms` 늘리기) |
| 30 | 등록되지 않은 키 | 중단 (활용신청 승인/키 확인. 승인 직후 1~2시간 반영 지연 있음) |

## 구성

| 클래스 | 역할 |
|---|---|
| `KraProperties` | `kra.*` 설정 |
| `KraApiClient` | URL 생성(서비스키 이중 인코딩 방지), 페이징, 재시도, 호출 수 집계 |
| `ResponseParser` | JSON/XML 오류 응답 해석, item 배열·단일 객체·빈 값 처리 |
| `RawStore` | 날짜별 원본 저장, 이어받기 판단 |
| `CollectService` | 기간 × 경마장 × API 수집 루프 |
| `CsvExporter` | 원본 → CSV (전체 필드 합집합) |
| `CollectRunner` | `kra.mode`에 따라 수집/내보내기 실행 |
