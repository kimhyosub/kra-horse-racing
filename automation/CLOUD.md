# 가치마를 클라우드 서버로 옮기기 (Oracle Cloud 무료)

```
방문자 ──https──> Cloudflare ──터널──> Oracle 서버 cloudflared ──> 가치마 웹 :8090
                                         ├─ 수집 타이머 (21:40 매일 · 금토일 08:40 · 11~19시 30분마다)
                                         ├─ 텔레그램 봇 (웹 안에서)
                                         └─ /srv/kra-data  (PC 의 C:\kra-data 와 같은 구조)
```

PC를 꺼도 사이트·수집·텔레그램이 돌아갑니다. 도메인·DNS는 바꾸지 않습니다 (터널만 서버로 옮김).
**데이터 옮기기(5단계)와 터널 옮기기(6단계)는 경주가 없는 월~목에 하세요.**

---

## 1. Oracle Cloud 가입

1. https://signup.cloud.oracle.com 에서 가입
2. **Home Region 은 `South Korea North (Chuncheon)` 또는 `South Korea Central (Seoul)`**
   - 나중에 못 바꿉니다. 무료 서버는 이 리전에서만 만들 수 있습니다.
   - 마사회 API 때문에 한국 리전이어야 합니다.
3. 카드 인증 (소액 승인 후 취소됨). Always Free 범위 안에서는 청구되지 않습니다.
4. **(권장) 가입이 끝나면 Pay As You Go 로 업그레이드**
   - 메뉴 → Billing & Cost Management → Upgrade and Manage Payment
   - 무료 계정은 서버가 7일 동안 한가하면 회수될 수 있는데, 업그레이드하면 그 규칙이 없습니다. 무료 한도 안에서는 여전히 0원입니다.
   - 같은 화면의 **Budgets** 에서 예산 1,000원·알림 메일을 걸어 두면 혹시 모를 과금을 바로 알 수 있습니다.

## 2. 서버 만들기

메뉴 → Compute → Instances → **Create instance**

| 항목 | 고를 것 |
|---|---|
| Name | gachima |
| Image | Change image → **Ubuntu** → Canonical Ubuntu **24.04** |
| Shape | Change shape → **Ampere** → `VM.Standard.A1.Flex` · OCPU **2** · Memory **12 GB** |
| Networking | 기본값 (새 VCN, public subnet, **Assign a public IPv4 address** 켜짐) |
| Add SSH keys | **Generate a key pair for me** → **Save private key** (파일 꼭 보관) |
| Boot volume | Specify a custom boot volume size → **100 GB** |

**Create** → 1~2분 뒤 RUNNING. 화면의 **Public IP address** 를 적어 둡니다.

- `Out of capacity` 가 뜨면: 몇 시간 뒤 다시 / Availability domain 을 바꿔서 / OCPU 1 · 12GB 로.
  (Pay As You Go 로 올리면 잘 잡히는 편입니다)
- 방화벽(Security List)은 건드리지 않습니다. 터널은 서버에서 밖으로 나가는 연결이라 포트를 열 필요가 없습니다.

## 3. PC 에서 서버 접속

명령 프롬프트(cmd):

```
mkdir %USERPROFILE%\.ssh
move %USERPROFILE%\Downloads\ssh-key-*.key %USERPROFILE%\.ssh\gachima.key
icacls %USERPROFILE%\.ssh\gachima.key /inheritance:r /grant:r "%USERNAME%:R"
notepad %USERPROFILE%\.ssh\config
```

메모장에 아래를 넣고 저장 (`1.2.3.4` 는 서버 공인 IP, 파일 이름에 .txt 가 붙지 않게):

```
Host gachima
    HostName 1.2.3.4
    User ubuntu
    IdentityFile ~/.ssh/gachima.key
    ServerAliveInterval 60
```

`ssh gachima` → 처음엔 `yes` → `ubuntu@gachima:~$` 가 나오면 성공.

## 4. 서버에 설치

### 4-1. 저장소 받기 (읽기 전용 배포 키)

서버(ssh gachima)에서:

```
ssh-keygen -t ed25519 -f ~/.ssh/github -N "" -C gachima-server
cat ~/.ssh/github.pub
```

나온 한 줄을 GitHub → kra-horse-racing → Settings → **Deploy keys** → Add deploy key
(Title `gachima-server`, **Allow write access 끔**) 에 붙여 넣고 저장. 다시 서버에서:

```
printf 'Host github.com\n  IdentityFile ~/.ssh/github\n' >> ~/.ssh/config
git clone git@github.com:kimhyosub/kra-horse-racing.git ~/kra-horse-racing
```

### 4-2. 설치 스크립트 (10분쯤)

```
bash ~/kra-horse-racing/automation/linux/install.sh
```

Java 17 · Maven · Python(pandas) · cloudflared 설치, 빌드, 서비스·타이머 등록까지 합니다. 웹은 아직 켜지 않습니다.

### 4-3. 비밀값 넣기

```
sudo nano /etc/gachima.env
```

PC 에서 지금 쓰는 값을 그대로 옮겨 적습니다. PC cmd 에서 `echo %KRA_SERVICE_KEY%` 처럼 확인할 수 있습니다.
`KRA_SERVICE_KEY`, `GACHIMA_LOGIN_USER`, `GACHIMA_LOGIN_PASSWORD`, `TELEGRAM_BOT_TOKEN`, `GACHIMA_TELEGRAM_CHATS`, `ANTHROPIC_API_KEY`
— 저장: `Ctrl+O` → Enter → `Ctrl+X`. **이 값들은 채팅·스크린샷에 올리지 마세요.**

## 5. 데이터 옮기기 (월~목)

PC 에서 (저장소 폴더):

```
git pull
powershell -ExecutionPolicy Bypass -File automation\push-to-server.ps1
```

1. PC 의 자동 수집·웹 작업을 **사용 안 함**으로 바꾸고(지우지 않음) PC 웹을 끕니다.
2. `C:\kra-data` 를 묶어(로그 제외) 서버로 보내고, 서버에서 풀어 웹과 수집 타이머를 켭니다.
3. 데이터 양에 따라 5~20분. PC 의 `C:\kra-data` 는 그대로 남습니다.

확인 (서버에서): `~/kra-horse-racing/automation/linux/status.sh` → gachima-web active, 웹 응답 200.

화면으로 확인하려면 PC 에서 `ssh -L 8091:localhost:8090 gachima` 를 켜 둔 채 브라우저로 http://localhost:8091
→ 로그인, 경주 화면, 검증 리포트, 가이드가 PC 때와 같으면 OK. 텔레그램 봇에 `/id` 를 보내 답이 오는지도 확인.

## 6. 터널을 서버로 옮기기

1. Cloudflare → Zero Trust → Networks → **Tunnels** → `gachima` → **Configure**
2. Overview 의 connector 설치 안내에서 **Debian**, **arm64** 선택 →
   `sudo cloudflared service install eyJ...` 로 시작하는 명령을 복사해 **서버(ssh gachima)에서** 실행
   (이 명령 속 토큰도 비밀값입니다)
3. Connectors 목록에 서버가 생기면, **PC 의 관리자 권한 cmd** 에서 PC 쪽 커넥터를 끕니다:
   ```
   sc stop cloudflared
   sc config cloudflared start= disabled
   ```
4. 휴대폰에서 https://gachima.co.kr → 로그인 → 정상이면 끝. Connectors 목록에 서버 하나만 남아야 합니다.

## 이제부터 쓰는 법

| 하고 싶은 것 | 방법 |
|---|---|
| 새 코드 반영 | PC 에서 `automation\deploy.bat` (git pull → 바뀐 것만 빌드 → 웹 재시작) |
| 상태 보기 | `ssh gachima ~/kra-horse-racing/automation/linux/status.sh` |
| 손으로 수집 | `ssh gachima ~/kra-horse-racing/automation/linux/kra-daily.sh --from 2026-11-27 --to 2026-11-29` (옵션은 PC 판과 같음: `--refresh` `--odds-only` `--skip-odds`) |
| 로그·파일 점검 | PC 에서 `powershell -ExecutionPolicy Bypass -File automation\pull-from-server.ps1` → `C:\kra-data\logs`, `C:\kra-data\web` 로 받아짐 (Claude 에게 확인 부탁할 때) |
| DB 백업 받기 | 위 명령에 `-Db` → `C:\kra-data\backup` (서버는 매일 04:10 자동 백업, 14일 보관) |
| 비밀값·설정 바꾸기 | `sudo nano /etc/gachima.env` → `sudo systemctl restart gachima-web` |
| 추가 사용자 | `nano /srv/kra-data/gachima-users.properties` → 웹 재시작 |
| 웹 로그 | `tail -f /srv/kra-data/logs/web.log` 또는 `journalctl -u gachima-web -f` |
| 타이머 시각 | `systemctl list-timers 'gachima-*'` |

## 되돌리기 (PC 로)

1. PC 에서 `automation\pull-from-server.ps1 -Db` 로 서버 DB 백업 받기 (그 사이 쌓인 기록)
2. 서버: `sudo systemctl stop gachima-web cloudflared gachima-daily.timer gachima-morning.timer gachima-results.timer`
3. PC 관리자 cmd: `sc config cloudflared start= auto` → `sc start cloudflared`
4. PC PowerShell: `Get-ScheduledTask -TaskPath \Gachima\ | Enable-ScheduledTask` → `Start-ScheduledTask -TaskPath \Gachima\ -TaskName Web`
