#!/usr/bin/env bash
# 가치마 서버에 새 코드 반영: git pull → 바뀐 모듈만 빌드 → 웹 재시작
#   ./deploy.sh              보통 (테스트 건너뜀, 1~3분)
#   ./deploy.sh --test       테스트까지
#   ./deploy.sh --all        바뀐 게 없어도 둘 다 다시 빌드
#   ./deploy.sh --no-restart 빌드만 (install.sh 가 처음에 씀)
# PC 에서:  automation\deploy.bat   (ssh gachima 로 이 스크립트를 실행)
set -euo pipefail
export LANG=C.UTF-8 LC_ALL=C.UTF-8   # 서버 기본 로캘(POSIX)이면 한글이 깨짐
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
TEST=0; ALL=0; RESTART=1
for a in "$@"; do
  case "$a" in --test) TEST=1 ;; --all) ALL=1 ;; --no-restart) RESTART=0 ;; *) echo "모르는 옵션: $a" >&2; exit 1 ;; esac
done
cd "$REPO"
OLD=$(git rev-parse HEAD)
echo "== git pull"
git pull --ff-only
NEW=$(git rev-parse HEAD)
changed() { [ $ALL = 1 ] || [ ! -f "$2" ] || ! git diff --quiet "$OLD" "$NEW" -- "$1"; }
MVN=(mvn -q -B package); [ $TEST = 1 ] || MVN+=(-DskipTests)

WEB_BUILT=0
if changed kra-collector kra-collector/target/kra-collector-1.0.0.jar; then
  echo "== 수집기 빌드"; (cd kra-collector && "${MVN[@]}")
fi
if changed kra-web kra-web/target/kra-web-0.1.0.jar; then
  echo "== 웹 빌드"; (cd kra-web && "${MVN[@]}"); WEB_BUILT=1
fi
if git diff --quiet "$OLD" "$NEW" -- automation/linux/install.sh; then :; else
  echo "!! install.sh 가 바뀌었습니다. 서비스 설정을 다시 쓰려면: bash automation/linux/install.sh"
fi
if [ $RESTART = 1 ] && [ $WEB_BUILT = 1 ]; then
  echo "== 웹 재시작"
  sudo systemctl restart gachima-web
  for i in $(seq 1 60); do
    if curl -fs -o /dev/null http://localhost:8090/about; then echo "웹 정상 (${i}초)"; break; fi
    sleep 1
    [ $i = 60 ] && { echo "!! 60초 안에 웹이 안 떴습니다:  journalctl -u gachima-web -n 80"; exit 1; }
  done
fi
echo "== 반영 끝: $(git log -1 --format='%h %s')"
