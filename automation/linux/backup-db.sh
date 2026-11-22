#!/usr/bin/env bash
# 가치마 DB 백업 (웹이 켜진 채로). 매일 04:10 타이머가 부르고, 14일치만 남긴다.
#   ./backup-db.sh                 → /srv/kra-data/backup/gachima-YYYYMMDD.zip
#   ./backup-db.sh /tmp/x.zip      → 그 파일로
# 되살리기: 웹을 끄고 java -cp h2.jar org.h2.tools.RunScript -url jdbc:h2:file:/srv/kra-data/db/gachima -user sa -script 파일.zip -options compression zip
set -euo pipefail
DATA=/srv/kra-data
OUT="${1:-$DATA/backup/gachima-$(date +%Y%m%d).zip}"
mkdir -p "$(dirname "$OUT")"
H2=$(ls ~/.m2/repository/com/h2database/h2/*/h2-*.jar 2>/dev/null | grep -v sources | sort -V | tail -1)
[ -n "$H2" ] || { echo "h2 jar 를 찾지 못했습니다 (~/.m2). deploy.sh 로 웹을 한 번 빌드하세요."; exit 1; }
java -cp "$H2" org.h2.tools.Script -url "jdbc:h2:file:$DATA/db/gachima;AUTO_SERVER=TRUE" -user sa -script "$OUT" -options compression zip
echo "백업: $OUT ($(du -h "$OUT" | cut -f1))"
[ $# -eq 0 ] && find "$DATA/backup" -name 'gachima-*.zip' -mtime +14 -delete
exit 0
