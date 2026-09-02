#!/bin/bash
LOGFILE=$(mktemp)
trap "rm -f $LOGFILE" EXIT

while true; do
  response=$(curl -s -o /dev/null -D - http://qrcode-api.k8s.orb.local/actuator/health)
  pod=$(echo "$response" | grep -i '^x-pod-name:' | tr -d '\r' | awk '{print $2}')
  status=$(echo "$response" | head -n 1 | awk '{print $2}')
  [ -z "$pod" ] && pod="NONE"
  [ -z "$status" ] && status="NONE"
  echo "${pod}|${status}" >> "$LOGFILE"

  clear
  total=$(wc -l < "$LOGFILE" | tr -d ' ')
  echo "Total de requisições: $total"
  echo ""
  echo "Por Pod:"
  awk -F'|' '{print $1}' "$LOGFILE" | sort | uniq -c | while read -r count p; do
    pct=$(( 100 * count / total ))
    echo "  $p: $count (${pct}%)"
  done
  echo ""
  echo "Por status HTTP:"
  awk -F'|' '{print $2}' "$LOGFILE" | sort | uniq -c | while read -r count s; do
    pct=$(( 100 * count / total ))
    echo "  $s: $count (${pct}%)"
  done
  sleep 0.3
done
