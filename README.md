# kra-horse-racing

한국마사회 공공데이터 기반 경마 데이터 수집·분석·모의베팅 도구

| 폴더 | 내용 | 언어 |
|---|---|---|
| `kra-collector/` | 공공데이터포털 API 수집기 (경주성적, 복승/통합 확정배당, 심판, 출전표) → 원본 JSON + CSV | Java 8 / Spring Boot 2.7 |
| `kra-analysis/` | 경주 시점 특징값 생성, 가설·규칙 검증, 시장+요인 모델 학습/검증 | Python (pandas, numpy, scipy) |
| `kra-raceday/` | 경주 당일 분석: 출전표 + 배당·마체중 입력 → 모델확률/기대값, 모의베팅 기록·정산 | Python (pandas, numpy) |

- 인증키는 코드에 넣지 않고 환경변수 `KRA_SERVICE_KEY`로만 사용합니다.
- 수집 데이터(`kra-data/`), 모의베팅 기록, 입력 양식은 커밋하지 않습니다.
- 모델/규칙은 **모의 검증용**이며, 실제 수익을 보장하지 않습니다.
