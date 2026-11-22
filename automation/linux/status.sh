#!/usr/bin/env bash
# 서버 상태 한눈에:  ./status.sh
DATA=/srv/kra-data
echo "== 서비스"; systemctl is-active gachima-web cloudflared 2>/dev/null | paste -d' ' <(echo -e "gachima-web\ncloudflared") -
echo "== 다음 수집"; systemctl list-timers 'gachima-*' --no-pager | head -5
echo "== 마지막 수집"; cat $DATA/web/collect-status.json 2>/dev/null; echo
echo "== 최근 로그"; ls -t $DATA/logs | head -5
echo "== 디스크"; df -h $DATA | tail -1
echo "== 웹 응답"; curl -s -o /dev/null -w '%{http_code} (%{time_total}s)\n' http://localhost:8090/about
