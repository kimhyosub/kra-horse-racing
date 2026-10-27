수집기(kra-collector) 결과 CSV로 분석하는 스크립트 (Python 3, pandas/numpy/scipy)

1) python api_features.py race.csv referee.csv features_api   # 경주 시점 기준 특징값 생성 (약 7분)
2) python api_analysis.py                                      # 사전등록 규칙 + 가설별 연도 검증
3) python api_model.py                                         # 시장+요인 모델 학습(2023H2~2024) / 검증(2025~2026), 가치베팅 회수율

* 스크립트 상단의 D 경로(/home/claude/api/)를 CSV 폴더로 바꿔서 사용
* 회수율은 최종 확정배당 기준. 실제 구매 시점 배당과 다를 수 있음
