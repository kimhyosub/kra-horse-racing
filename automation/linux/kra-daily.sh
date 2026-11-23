#!/usr/bin/env bash
# 가치마 자동 수집 (리눅스 서버판, automation/kra-daily.ps1 과 같은 일)
#   수집기(출전표·경주성적·심판·확정배당) → CSV → export_web.py → $DATA/web → 웹이 1분 안에 불러옴
#
#   기본 범위: 어제 ~ 내일
#   ./kra-daily.sh --from 2026-11-20 --to 2026-11-22
#   ./kra-daily.sh --skip-odds            확정배당(큰 파일) 빼고
#   ./kra-daily.sh --odds-only            확정배당만 (지난 날짜 승식 배당 채우기)
#   ./kra-daily.sh --refresh              이미 받은 날짜도 다시 (새로 받은 게 더 적으면 기존 파일 유지)
#   ./kra-daily.sh --quiet                낮 동안 결과 반영용 (results-*.log, 3일 보관)
#   그 밖에: --back-days N --ahead-days N --meets 1,2,3,4 --skip-collect --skip-export
#
# 설정·비밀값은 /etc/gachima.env (KRA_SERVICE_KEY 등). systemd 가 읽어 넘기고, 손으로 돌릴 때는 이 스크립트가 읽는다.
# 종료 코드: 0 정상, 1 실패, 3 일부 실패(계속 진행함)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# 윈도우에서 만든 파일이면 줄 끝 \r 을 떼고 읽는다
if [ -z "${KRA_SERVICE_KEY:-}" ] && [ -r /etc/gachima.env ]; then set -a; . <(tr -d '\r' < /etc/gachima.env); set +a; fi
for v in GACHIMA_DATA GACHIMA_IMPORT_DIR KRA_SERVICE_KEY; do [ -n "${!v:-}" ] && printf -v "$v" '%s' "${!v//$'\r'/}"; done

DATA="${GACHIMA_DATA:-/srv/kra-data}"
WEB="${GACHIMA_IMPORT_DIR:-$DATA/web}"
JAVA="${JAVA:-java}"
PYTHON="${PYTHON:-python3}"
JAR="$REPO/kra-collector/target/kra-collector-1.0.0.jar"
RACEDAY="$REPO/kra-raceday"
KEEP_LOG_DAYS=30

FROM=""; TO=""; BACK=1; AHEAD=1; MEETS="1,2,3,4"
SKIP_ODDS=0; ODDS_ONLY=0; REFRESH=0; SKIP_COLLECT=0; SKIP_EXPORT=0; QUIET=0
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift ;;
    --to) TO="$2"; shift ;;
    --back-days) BACK="$2"; shift ;;
    --ahead-days) AHEAD="$2"; shift ;;
    --meets) MEETS="$2"; shift ;;
    --skip-odds) SKIP_ODDS=1 ;;
    --odds-only) ODDS_ONLY=1 ;;
    --refresh) REFRESH=1 ;;
    --skip-collect) SKIP_COLLECT=1 ;;
    --skip-export) SKIP_EXPORT=1 ;;
    --quiet) QUIET=1 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "모르는 옵션: $1" >&2; exit 1 ;;
  esac
  shift
done

mkdir -p "$DATA/logs" "$WEB"
STARTED="$(date '+%Y-%m-%d %H:%M:%S')"
KIND=$([ $QUIET = 1 ] && echo results || echo daily)
LOG="$DATA/logs/$KIND-$(date +%Y%m%d-%H%M).log"
log() { local l; l="$(date +%H:%M:%S) $*"; echo "$l"; echo "$l" >> "$LOG"; }

# 같은 작업이 겹쳐 돌지 않게
exec 9>"$DATA/.daily.lock"
if ! flock -n 9; then echo "이미 실행 중입니다."; exit 0; fi

day() {  # 2026-11-20 또는 20261120 → 2026-11-20
  local s="$1"
  [[ "$s" =~ ^[0-9]{8}$ ]] && s="${s:0:4}-${s:4:2}-${s:6:2}"
  [[ "$s" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "날짜 형식이 아닙니다: $1 (예: 2026-11-20)" >&2; return 1; }
  date -d "$s" +%Y-%m-%d 2>/dev/null || { echo "날짜 형식이 아닙니다: $1 (예: 2026-11-20)" >&2; return 1; }
}
if [ -n "$FROM" ]; then F=$(day "$FROM") || exit 1; else F=$(date -d "-$BACK day" +%Y-%m-%d); fi
if [ -n "$TO" ]; then T=$(day "$TO") || exit 1; else T=$(date -d "+$AHEAD day" +%Y-%m-%d); fi
if [[ "$F" > "$T" ]]; then echo "시작일이 종료일보다 늦습니다: $F ~ $T" >&2; exit 1; fi

status() {  # 웹 관리 화면의 '수집 상태'
  local ok="$1" msg="$2"
  [ $QUIET = 1 ] && msg="[결과 반영] $msg"
  "$PYTHON" - "$WEB/collect-status.json" "$STARTED" "$ok" "$F" "$T" "$msg" "$LOG" <<'PY'
import json, sys, datetime
p, st, ok, f, t, msg, log = sys.argv[1:]
json.dump(dict(startedAt=st, finishedAt=datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S'), ok=ok == 'true',
               **{'from': f}, to=t, message=msg, log=log), open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
PY
}
fail() { log "실패: $*"; status false "$*"; cleanup; exit 1; }
cleanup() {
  find "$DATA/logs" -name 'daily-*.log' -mtime +$KEEP_LOG_DAYS -delete 2>/dev/null
  find "$DATA/logs" -name 'results-*.log' -mtime +3 -delete 2>/dev/null
}
run() { "$@" 2>&1 | while IFS= read -r l; do log "  $l"; done; return "${PIPESTATUS[0]}"; }
tf() { [ "$1" = 1 ] && echo true || echo false; }

EXIT=0
log "가치마 자동 수집 $F ~ $T, 경마장 $MEETS"

if [ $SKIP_COLLECT = 0 ]; then
  [ -n "${KRA_SERVICE_KEY:-}" ] || fail "KRA_SERVICE_KEY 가 없습니다 (/etc/gachima.env)"
  [ -f "$JAR" ] || fail "수집기 jar 가 없습니다: $JAR (deploy.sh 로 빌드)"
  APIS="race-result,referee,entry-sheet"
  [ $SKIP_ODDS = 0 ] && APIS="$APIS,all-odds"
  [ $ODDS_ONLY = 1 ] && APIS="all-odds"
  NOT_ODDS_ONLY=$([ $ODDS_ONLY = 1 ] && echo 0 || echo 1)
  WANT_ODDS=$([ $ODDS_ONLY = 1 ] || [ $SKIP_ODDS = 0 ] && echo 1 || echo 0)
  # 웹용 파일은 확정배당 원본(JSON)을 직접 읽으므로, 확정배당만 받을 때는 큰 CSV 를 다시 쓰지 않는다
  EXPORT_APIS=$([ $ODDS_ONLY = 1 ] && echo referee || echo "$APIS")
  log "[1/2] 수집기: $APIS"
  run "$JAVA" -Dfile.encoding=UTF-8 -jar "$JAR" \
      --kra.from="$F" --kra.to="$T" --kra.meets="$MEETS" --kra.output-dir="$DATA" \
      --kra.apis.entry-sheet.enabled=$(tf $NOT_ODDS_ONLY) --kra.apis.quinella-odds.enabled=false \
      --kra.apis.race-result.enabled=$(tf $NOT_ODDS_ONLY) --kra.apis.referee.enabled=$(tf $NOT_ODDS_ONLY) \
      --kra.apis.all-odds.enabled=$(tf $WANT_ODDS) --kra.export-apis="$EXPORT_APIS" \
      --kra.skip-existing=$( [ $REFRESH = 1 ] && echo false || echo true )
  rc=$?
  case $rc in
    0) ;;
    2) fail "수집기가 호출 한도 또는 인증키 문제로 중단했습니다 (로그 확인)" ;;
    3) log "  일부 날짜 수집 실패 → 받은 것으로 계속"; EXIT=3 ;;
    *) fail "수집기 오류 (종료 코드 $rc)" ;;
  esac
fi

if [ $SKIP_EXPORT = 0 ]; then
  log "[2/2] 웹용 파일 만들기 (export_web.py)"
  ( cd "$RACEDAY" && run "$PYTHON" export_web.py --from "${F//-/}" --to "${T//-/}" \
      --entries "$DATA/entry-sheet.csv" --history "$DATA/race-result.csv" --referee "$DATA/referee.csv" \
      --raw "$DATA/raw" --out "$WEB" )
  rc=$?
  [ $rc = 0 ] || fail "export_web.py 오류 (종료 코드 $rc)"
fi

MSG=$([ $EXIT = 3 ] && echo "완료 (일부 날짜 수집 실패)" || echo "완료")
log "$MSG"
status true "$MSG"
cleanup
exit $EXIT
