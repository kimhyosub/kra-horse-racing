#!/usr/bin/env bash
# PC 에서 보낸 kra-data 묶음을 /srv/kra-data 에 풀고 웹을 켠다. (push-to-server.ps1 가 부름)
#   ./import-data.sh ~/kra-data.tgz
set -euo pipefail
TGZ="${1:?묶음 파일 경로}"
DATA=/srv/kra-data
[ -f "$TGZ" ] || { echo "파일이 없습니다: $TGZ"; exit 1; }
echo "== 웹 끄기"
sudo systemctl stop gachima-web || true
if [ -f $DATA/db/gachima.mv.db ]; then
  B=$DATA/db/before-import-$(date +%Y%m%d-%H%M).mv.db
  mv $DATA/db/gachima.mv.db "$B"; echo "   서버에 있던 DB 는 $B 로 보관"
fi
rm -f $DATA/db/*.lock.db $DATA/db/*.trace.db
echo "== 풀기 ($(du -h "$TGZ" | cut -f1))"
# 묶음 안은 kra-data/… → /srv/kra-data/…  (파일 시각 유지: 확정 여부 판단에 씀)
tar -xzf "$TGZ" -C $DATA --strip-components=1
echo "== 웹 켜기"
sudo systemctl start gachima-web
for i in $(seq 1 90); do
  if curl -fs -o /dev/null http://localhost:8090/about; then echo "웹 정상 (${i}초)"; break; fi
  sleep 1; [ $i = 90 ] && { echo "!! 웹이 안 떴습니다:  journalctl -u gachima-web -n 80"; exit 1; }
done
echo "== 수집 타이머 켜기"
sudo systemctl start gachima-daily.timer gachima-morning.timer gachima-results.timer gachima-backup.timer
echo "== 들어온 것"
ls $DATA/web | tail -5; du -sh $DATA/raw $DATA/db 2>/dev/null
rm -f "$TGZ"
