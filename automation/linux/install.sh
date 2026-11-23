#!/usr/bin/env bash
# 가치마 서버 설치 (Ubuntu 22.04/24.04, ARM·x86 모두). 여러 번 돌려도 됩니다.
#   bash ~/kra-horse-racing/automation/linux/install.sh
# 하는 일: 시간대(서울) · Java 17 · Maven · Python(pandas) · cloudflared 설치
#          /srv/kra-data 폴더 · /etc/gachima.env 만들기 · 빌드 · systemd 서비스와 타이머 등록
# 웹은 등록만 하고 켜지 않습니다 (PC 데이터를 옮긴 뒤 import-data.sh 가 켬).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ME="$(id -un)"
DATA=/srv/kra-data

echo "== 1/6 시간대 Asia/Seoul"
sudo timedatectl set-timezone Asia/Seoul

echo "== 2/6 프로그램 설치"
# 새 서버는 켜자마자 자동 업데이트가 apt 를 잡고 있어서, 끝날 때까지 기다린다 (최대 10분)
wait_apt() {
  for i in $(seq 1 120); do
    { pgrep -x apt || pgrep -x apt-get || pgrep -x dpkg || pgrep -f unattended-upgrade; } >/dev/null || return 0
    [ $i = 1 ] && echo "   자동 업데이트가 끝나기를 기다리는 중…"
    sleep 5
  done
}
APT="sudo apt-get -o DPkg::Lock::Timeout=600"
wait_apt
$APT update -q
sudo DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=600 install -y -q openjdk-17-jdk-headless maven python3 python3-numpy python3-pandas git curl
if ! command -v cloudflared >/dev/null; then
  sudo mkdir -p --mode=0755 /usr/share/keyrings
  curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
  echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main" | sudo tee /etc/apt/sources.list.d/cloudflared.list >/dev/null
  wait_apt
  $APT update -q
  $APT install -y -q cloudflared
fi
command -v cloudflared >/dev/null || { echo "!! cloudflared 설치 실패. 잠시 뒤 install.sh 를 다시 돌리세요."; exit 1; }

echo "== 3/6 데이터 폴더 $DATA"
sudo mkdir -p $DATA/{web,db,logs,uploads,raw,backup}
sudo chown -R "$ME:$ME" $DATA

echo "== 4/6 설정 파일 /etc/gachima.env"
if [ ! -f /etc/gachima.env ]; then
  sudo install -m 640 -o root -g "$ME" "$HERE/gachima.env.example" /etc/gachima.env
  echo "   새로 만들었습니다. 비밀값을 넣으세요:  sudo nano /etc/gachima.env"
else
  echo "   이미 있음 (그대로 둠)"
fi

echo "== 5/6 빌드 (처음엔 라이브러리를 받느라 5분쯤)"
bash "$HERE/deploy.sh" --all --no-restart

echo "== 6/6 서비스·타이머 등록"
JAVA=$(command -v java)
unit() { sudo tee "/etc/systemd/system/$1" >/dev/null; }

unit gachima-web.service <<U
[Unit]
Description=가치마 웹 (8090)
After=network-online.target
Wants=network-online.target

[Service]
User=$ME
EnvironmentFile=/etc/gachima.env
WorkingDirectory=$REPO/kra-web
ExecStart=$JAVA -Xms256m -Xmx2g -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Seoul -jar $REPO/kra-web/target/kra-web-0.1.0.jar --logging.file.name=$DATA/logs/web.log --logging.charset.file=UTF-8 --logging.logback.rollingpolicy.max-history=14
SuccessExitStatus=143
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
U

job() {  # 이름 설명 실행명령 시각 제한분
  unit "gachima-$1.service" <<U
[Unit]
Description=가치마: $2
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$ME
EnvironmentFile=/etc/gachima.env
ExecStart=$3
TimeoutStartSec=${5}min
SuccessExitStatus=3
U
  unit "gachima-$1.timer" <<U
[Unit]
Description=가치마: $2

[Timer]
OnCalendar=$4
Persistent=true

[Install]
WantedBy=timers.target
U
}
D="$HERE/kra-daily.sh"
job daily   "어제~내일 출전표·경주성적·확정배당 수집 → 웹 파일"    "$D"                                                "*-*-* 21:40:00"             120
job morning "경주일 아침 출전표 다시 받기 (기수 변경·출전 취소)" "$D --back-days 0 --ahead-days 0 --skip-odds"         "Fri,Sat,Sun *-*-* 08:40:00" 60
job results "낮 동안 30분마다 오늘 결과 반영"                  "$D --back-days 0 --ahead-days 0 --skip-odds --quiet" "*-*-* 11..19:00,30:00"      25
job backup  "DB 백업 (14일 보관)"                            "$HERE/backup-db.sh"                                "*-*-* 04:10:00"             10

sudo systemctl daemon-reload
sudo systemctl enable gachima-web.service >/dev/null
# 수집 타이머는 데이터를 옮긴 뒤 import-data.sh 가 켠다 (이미 켜져 있으면 그대로)
sudo systemctl enable gachima-daily.timer gachima-morning.timer gachima-results.timer gachima-backup.timer >/dev/null

# deploy.sh 가 비밀번호 없이 웹을 재시작할 수 있게
echo "$ME ALL=(root) NOPASSWD: /usr/bin/systemctl restart gachima-web, /usr/bin/systemctl start gachima-web, /usr/bin/systemctl stop gachima-web" \
  | sudo tee /etc/sudoers.d/gachima >/dev/null
sudo chmod 440 /etc/sudoers.d/gachima

echo
echo "설치 끝. 다음 순서:"
echo "  1) sudo nano /etc/gachima.env   (비밀값 넣기)"
echo "  2) PC 에서 automation\\push-to-server.ps1   (데이터 옮기기 → 웹이 켜짐)"
echo "  3) Cloudflare 터널 커넥터를 이 서버에 설치 (CLOUD.md 4단계)"
systemctl list-timers 'gachima-*' --no-pager || true
