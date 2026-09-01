#!/bin/bash
LOGFILE=$(mktemp)
trap "rm -f $LOGFILE" EXIT

while true; do
  pod=$(curl -s -o /dev/null -D - http://qrcode-api.k8s.orb.local/actuator/health \
        | grep -i 'x-pod-name:' | tr -d '\r' | awk '{print $2}')
  echo "$pod" >> "$LOGFILE"

  clear
  total=$(wc -l < "$LOGFILE" | tr -d ' ')
  echo "Total de requisições: $total"
  sort "$LOGFILE" | uniq -c | while read -r count p; do
    pct=$(( 100 * count / total ))
    echo "  $p: $count (${pct}%)"
  done
  sleep 0.3
done
