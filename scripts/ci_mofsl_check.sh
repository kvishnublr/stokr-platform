#!/bin/bash
set -euo pipefail

# ci_mofsl_check.sh
# Reports whether the Motilal Oswal (MO API) session works on the production server, from the
# stokr-lite service log. Output is sanitised for public Actions logs: no client codes, no exact
# balances — only login success/failure (with MO's reason) and whether the last margin reading
# was above ₹10 lakh.

DEPLOY_HOST="${DEPLOY_HOST:?DEPLOY_HOST not set}"
DEPLOY_USER="${DEPLOY_USER:-root}"
SSH_KEY="$HOME/.ssh/github_actions_deploy"
SSH_OPTS="-i $SSH_KEY -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o ConnectTimeout=30"

# The unit may send stdout to a file (StandardOutput=append:/path) instead of the journal, so
# read whichever it uses. Diagnostics (log source, startup account counts) go to stderr.
REMOTE='LOGFILE=$(systemctl cat stokr-lite 2>/dev/null | sed -n "s/^StandardOutput=append://p" | tail -1)
if [ -n "$LOGFILE" ] && [ -f "$LOGFILE" ]; then
  echo "log source: service log file" >&2
  SRC="tail -n 200000 $LOGFILE"
else
  echo "log source: journal" >&2
  SRC="journalctl -u stokr-lite --since -6h --no-pager -o short-iso"
fi
$SRC 2>/dev/null | grep -o "MOFSL: startup found.*" | tail -2 >&2
$SRC 2>/dev/null | grep -E "MOFSL" | grep -E "login successful|login failed|startup login|margin fetch failed|available margin=" | tail -20'

LINES=$(ssh $SSH_OPTS "${DEPLOY_USER}@${DEPLOY_HOST}" "$REMOTE" 2> >(sed -E 's/[0-9]{4,}/####/g' >&2) || true)

if [ -z "$LINES" ]; then
  echo "No MOFSL login/margin lines in the stokr-lite log."
  echo "(The service logs a login attempt at every start if a Motilal account is connected.)"
  exit 0
fi

echo "$LINES" | while IFS= read -r line; do
  ts=$(echo "$line" | awk '{print $1}')
  line=$(echo "$line" | sed -E 's/clientCode=[^ ,]+/clientCode=***/g; s/failed for [^:]+:/failed:/g')
  if echo "$line" | grep -q 'available margin='; then
    amt=$(echo "$line" | sed -E 's/.*available margin=([0-9.Ee+-]+).*/\1/')
    if awk -v a="$amt" 'BEGIN { exit !(a+0 >= 1000000) }'; then
      echo "$ts  MARGIN OK: available margin is ≥ ₹10,00,000"
    else
      echo "$ts  MARGIN LOW: available margin is below ₹10,00,000"
    fi
  elif echo "$line" | grep -qE 'login successful'; then
    echo "$ts  LOGIN OK"
  else
    # Error text can quote MO's response; mask any long numbers (amounts, codes) before printing.
    echo "$ts  PROBLEM: $(echo "$line" | sed -E 's/.*(MOFSL[^:]*: ?)//; s/[0-9]{4,}/####/g' | cut -c1-160)"
  fi
done
