# 밖에서 휴대폰으로 쓰기 (Cloudflare Tunnel + 로그인 + 스크린샷)

```
휴대폰 ──https──> Cloudflare ──터널──> 집 PC cloudflared ──> http://localhost:8090 (가치마 웹, 로그인)
                                                                  └─ 스크린샷 → Claude API → 입력칸 채우기
```

집 PC가 켜져 있고 웹이 떠 있어야 합니다. 경주일에는 **절전 모드를 끄세요**
(설정 → 시스템 → 전원 → 화면/절전 → 절전 "안 함").

## 1. 환경변수 (cmd, 한 번)

```
setx GACHIMA_LOGIN_USER "원하는아이디"
setx GACHIMA_LOGIN_PASSWORD "긴비밀번호"
setx ANTHROPIC_API_KEY "sk-ant-..."
```

- 비밀번호는 다른 곳에서 쓰지 않는 긴 것으로. 인터넷에 열리는 주소입니다.
- Claude API 키: https://console.anthropic.com → API Keys. 결제 수단 등록 필요. 스크린샷 한 번에 대략 10~20원.
- 키·비밀번호는 저장소나 채팅에 쓰지 마세요.
- 새로 연 창·다시 실행한 프로그램부터 적용 (IntelliJ로 돌리면 IntelliJ 재시작).

## 2. 웹을 항상 띄우기

```
cd kra-web
mvn clean package
cd ..
powershell -ExecutionPolicy Bypass -File automation\register-task.ps1 -Web
```

로그인하면 웹이 숨김 창으로 뜹니다 (로그: `C:\kra-data\logs\web-*.log`). 지금 바로 띄우려면
`Start-ScheduledTask -TaskPath \Gachima\ -TaskName Web`. IntelliJ에서 같이 돌리면 8090 포트가 겹치니 하나만.

DB는 `C:\kra-data\db\gachima.mv.db` 입니다 (어디서 실행해도 같은 파일). 처음 뜰 때 비어 있어도
`C:\kra-data\web`의 day-*.json, paper-bets.json 을 1분 안에 다시 불러옵니다.

## 3. Cloudflare Tunnel

### 먼저 시험 (주소가 매번 바뀜)

1. https://github.com/cloudflare/cloudflared/releases 에서 `cloudflared-windows-amd64.exe` 받아 `cloudflared.exe`로 이름 변경
2. `cloudflared.exe tunnel --url http://localhost:8090`
3. 화면에 나오는 `https://xxxx.trycloudflare.com` 을 휴대폰에서 열어 로그인

창을 닫거나 PC를 다시 켜면 주소가 바뀌므로 시험용입니다.

### 고정 주소 (권장)

도메인이 하나 필요합니다 (Cloudflare에서 사면 .com 연 1만 원대. 다른 곳에서 산 도메인은 네임서버를 Cloudflare로 옮김).

1. Cloudflare 대시보드 → Zero Trust → Networks → Tunnels → **Create a tunnel** → Cloudflared → 이름 `gachima`
2. 운영체제 Windows 선택 → 나오는 설치 명령(`cloudflared.exe service install eyJ...`)을
   **관리자 권한 cmd**에서 실행 → 윈도우 서비스로 등록되어 PC 켜면 자동 연결
3. Public Hostname 추가: 예) `gachima.내도메인.com` → Service `HTTP` / `localhost:8090`
4. 휴대폰에서 `https://gachima.내도메인.com` → 로그인 (로그인 유지 30일)

## 4. 경주일 쓰는 법

1. 휴대폰에서 경주 → **당일 분석**
2. 더비온 단승 배당·마체중 화면 스크린샷 → **스크린샷으로 채우기** (여러 장 한 번에, 최대 5장)
3. 채워진 칸 확인 (못 읽은 마번·다른 경주 화면이면 경고) → **다시 계산** = 저장
4. 출발 전 마지막 저장이 공식 모의 기록. 출발 후에는 잠김.

## 문제가 생기면

- 로그인 화면이 안 뜸: PC 웹이 떠 있는지 (`http://localhost:8090` PC에서), cloudflared 서비스가 실행 중인지
- "스크린샷 읽기가 꺼져 있습니다": ANTHROPIC_API_KEY 설정 후 웹 다시 실행
- "Claude API 오류 401": 키가 틀림 / "400 … credit": 결제 잔액 부족
- 로그: `C:\kra-data\logs\web-*.log`
