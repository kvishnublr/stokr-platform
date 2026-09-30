#!/bin/bash
set -euo pipefail

# ci_trade_diag.sh
# Read-only summary of today's closed trades on the production server (paper + live), to diagnose
# why they lost: counts / P&L by strategy and exit reason, plus sample rows with leg prices.
# Selects no user, account or broker-credential columns.

DEPLOY_HOST="${DEPLOY_HOST:?DEPLOY_HOST not set}"
DEPLOY_USER="${DEPLOY_USER:-root}"
SSH_KEY="$HOME/.ssh/github_actions_deploy"
SSH_OPTS="-i $SSH_KEY -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o ConnectTimeout=30"

ssh $SSH_OPTS "${DEPLOY_USER}@${DEPLOY_HOST}" 'bash -s' <<'REMOTE'
set -u
# Use the running app's own datasource settings (its process environment, then the unit's
# Environment=), else the application.yml defaults. Nothing here is printed.
PID=$(systemctl show stokr-lite -p MainPID --value 2>/dev/null)
ENV=""
[ -n "$PID" ] && [ "$PID" != "0" ] && [ -r /proc/$PID/environ ] && ENV=$(tr '\0' '\n' < /proc/$PID/environ)
ENV="$ENV
$(systemctl show stokr-lite -p Environment --value 2>/dev/null | tr ' ' '\n')"
CMD=$([ -n "$PID" ] && tr '\0' '\n' < /proc/$PID/cmdline 2>/dev/null)
get() { { echo "$ENV" | sed -n "s/^$1=//p"; echo "$CMD" | sed -n "s/^--spring\.datasource\.$2=//p"; } | head -1; }
URL=$(get SPRING_DATASOURCE_URL url); USER_=$(get SPRING_DATASOURCE_USERNAME username); PASS=$(get SPRING_DATASOURCE_PASSWORD password)
URL=${URL:-jdbc:postgresql://localhost:5432/stokr_lite}
HOSTPORT=$(echo "$URL" | sed -E 's#jdbc:postgresql://([^/]+)/.*#\1#'); DB=$(echo "$URL" | sed -E 's#.*/([^/?]+).*#\1#')
export PGHOST=${HOSTPORT%%:*} PGPORT=${HOSTPORT##*:} PGDATABASE=$DB PGUSER=${USER_:-stokr} PGPASSWORD=${PASS:-stokr_pass}
q() { psql -X -q -P pager=off -c "$1" 2>&1; }

echo "server time: $(date)   db now(): $(psql -X -tA -c 'select now()' 2>&1 | head -1)"
echo "datasource from: $([ -n "$URL$USER_$PASS" ] && echo 'running app' || echo 'defaults')"

# Exit reasons straight from today's service log (works even without DB access).
LOGFILE=$(systemctl cat stokr-lite 2>/dev/null | sed -n "s/^StandardOutput=append://p" | tail -1)
if [ -n "$LOGFILE" ] && [ -f "$LOGFILE" ]; then
  TODAY=$(date +%F)
  echo
  echo "=== Service log $TODAY: exit events by type ==="
  grep "^$TODAY" "$LOGFILE" | grep -oE "(STOP_LOSS|AUTO_EXIT|PER_POS_SL|PER_POS_TARGET|TRAILING_SL_HIT|TIME_EXIT|EOD_320_SQUAREOFF|AUTO_LOSS_REENTRY|AUTO_PROFIT_EXIT|TARGET_HIT|MAX_HOLD_[0-9]+D|EXPIRED)[: ]" | sort | uniq -c | sort -rn
  echo
  echo "=== Service log $TODAY: first 40 exit lines ==="
  grep "^$TODAY" "$LOGFILE" | grep -E "STOP_LOSS:|AUTO_EXIT:|PER_POS_SL:|PER_POS_TARGET:|TRAILING_SL_HIT:|TIME_EXIT:|squared off|AUTO_LOSS_REENTRY" | sed -E 's/^([0-9T:.-]+)[^ ]* .*(STOP_LOSS|AUTO_EXIT|PER_POS|TRAILING|TIME_EXIT|squared off|AUTO_LOSS)/\1 \2/' | cut -c1-220 | head -40
fi
echo
echo "=== F&O positions closed today: by strategy / exit reason ==="
q "select strategy_type, exit_reason, count(*) n,
          round(sum(current_pnl)) total_pnl, round(avg(current_pnl)) avg_pnl,
          round(min(current_pnl)) worst, round(max(current_pnl)) best,
          round(avg(lots),1) avg_lots,
          round(avg(extract(epoch from (exited_at - entered_at))/60)) avg_hold_min
     from live_positions
    where exited_at >= current_date
    group by 1,2 order by 1, n desc;"
echo
echo "=== F&O: 12 worst trades today (legs with entry/exit prices) ==="
q "select id, strategy_type, underlying, lots, lot_size, round(current_pnl) pnl, exit_reason,
          to_char(entered_at,'HH24:MI:SS') entry_t, to_char(exited_at,'HH24:MI:SS') exit_t,
          left(legs_json, 700) legs
     from live_positions where exited_at >= current_date
    order by current_pnl asc limit 12;"
echo
echo "=== F&O: entries per hour today (re-entry loops show as bursts) ==="
q "select to_char(entered_at,'HH24') hr, strategy_type, count(*) from live_positions
    where entered_at >= current_date group by 1,2 order by 1,2;"
echo
echo "=== Cash positions closed today: by strategy / exit reason ==="
q "select strategy_type, error_message exit_reason, side, count(*) n,
          round(sum(current_pnl)) total_pnl, round(avg(current_pnl)) avg_pnl,
          round(avg(extract(epoch from (exited_at - entered_at))/60)) avg_hold_min
     from cash_positions where exited_at >= current_date
    group by 1,2,3 order by 1, n desc;"
echo
echo "=== Cash: all trades today ==="
q "select id, symbol, strategy_type, side, quantity qty, entry_price, exit_price, target_price, stop_loss_price,
          round(current_pnl) pnl, error_message exit_reason,
          to_char(entered_at,'HH24:MI:SS') entry_t, to_char(exited_at,'HH24:MI:SS') exit_t
     from cash_positions where exited_at >= current_date order by exited_at limit 40;"
REMOTE
