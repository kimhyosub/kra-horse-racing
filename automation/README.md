# automation — 가치마 자동 수집

매일 밤 한 번:

```
수집기 (어제~내일, 서울·제주·부경·영천)        → C:\kra-data\*.csv
  출전표 · 경주성적 · 심판 · 확정배당
export_web.py (어제~내일 중 경주가 있는 날)    → C:\kra-data\web\day-YYYYMMDD.json
kra-web (1분마다 폴더 확인)                    → 전날 예상 / 확정 착순 / 성적 자동 반영
```

밤 21:40에 돌면 **오늘 경주 결과**와 **내일 출전표(전날 예상)** 가 함께 들어옵니다.
경주가 없는 날은 받을 것이 없어서 금방 끝납니다.

## 처음 한 번

1. 인증키를 사용자 환경변수로 (명령 프롬프트, 키는 코드·저장소에 쓰지 않음)
   ```
   setx KRA_SERVICE_KEY "발급받은_일반인증키"
   ```
   새로 연 창부터 적용됩니다.
2. 수집기 jar 만들기
   ```
   cd kra-collector
   mvn clean package
   ```
   → `kra-collector\target\kra-collector-1.0.0.jar`
3. 경로가 기본값(`C:\kra-data`, `java`, `python`)과 다르면 `kra-auto.config.example.ps1`을
   `kra-auto.config.ps1`로 복사해서 고칩니다.
4. 손으로 한 번 실행해서 확인: `kra-daily.bat` 더블클릭
   - 로그: `C:\kra-data\logs\daily-yyyyMMdd-HHmm.log`
   - 웹 관리 화면(http://localhost:8090/admin) **자동 수집** 칸에 결과가 보입니다.
5. 작업 스케줄러에 등록 (PowerShell, 관리자 권한 필요 없음)
   ```
   powershell -ExecutionPolicy Bypass -File automation\register-task.ps1
   powershell -ExecutionPolicy Bypass -File automation\register-task.ps1 -Morning 08:40   # 선택
   ```
   - `Daily`: 매일 21:40 (`-Time 22:10`처럼 바꿀 수 있음)
   - `RaceMorning`(선택): 금·토·일 08:40에 오늘 출전표만 다시 받기 (기수 변경·출전 취소 반영, 배당 생략)
   - `Results`(선택, `-Results`): 매일 11:00~19:00 30분마다 **오늘** 경주성적만 받아 웹에 반영
     → 경주가 끝나고 30분 안에 확정 착순·모의 장부 정산·성적 화면이 바뀝니다. 배당(all-odds)은 밤 Daily 에서.
     시간 조정: `-ResultsFrom 10:30 -ResultsHours 9 -ResultsEvery 20`. 로그는 `results-*.log` (3일 보관)
   - 로그인한 동안에만 돕니다. 그 시각에 PC가 꺼져 있었으면 켠 뒤 바로 한 번 실행합니다.
   - 지우기: `register-task.ps1 -Remove`

## 손으로 기간 지정

```
kra-daily.bat -From 2026-11-20 -To 2026-11-22
kra-daily.bat -From 20261120 -To 20261122 -SkipOdds
```

| 옵션 | 뜻 |
|---|---|
| `-From`, `-To` | 기간 (`2026-11-20`, `20261120` 둘 다 됨). 없으면 어제~내일 |
| `-BackDays`, `-AheadDays` | 기본 범위 조정 (기본 1, 1) |
| `-Meets 1,3` | 경마장 (기본 1,2,3,4) |
| `-SkipOdds` | 확정배당(all-odds) 생략. 400MB CSV를 다시 만들지 않아 빠름 |
| `-SkipCollect` | 수집 없이 export_web.py만 |
| `-SkipExport` | 수집만 |

## 결과 확인

- 종료 코드: 0 정상, 1 실패, 3 일부 날짜 수집 실패(나머지는 진행). 작업 스케줄러의 "마지막 실행 결과"에 보입니다.
- `C:\kra-data\web\collect-status.json`에 마지막 실행 결과를 남기고, 웹 관리 화면이 이 파일을 보여줍니다.
- 호출 수: 하루 실행에 많아야 수십 건 (한도 2,800). 이미 확정된 날은 다시 받지 않습니다.
- 수집기 오류 메시지에 요청 주소가 섞여도 인증키는 `serviceKey=***`로 가려서 로그에 남깁니다.

## 웹 자동 실행 · 밖에서 접속

`register-task.ps1 -Web` 으로 로그인할 때 가치마 웹을 띄웁니다. 휴대폰으로 밖에서 쓰는 설정은 [REMOTE.md](REMOTE.md).
