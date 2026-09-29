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

LINES=$(ssh $SSH_OPTS "${DEPLOY_USER}@${DEPLOY_HOST}" \
  "journalctl -u stokr-lite --since '-6 hours' --no-pager -o short-iso 2>/dev/null | grep -E 'MOFSL' | grep -E 'login successful|login failed|startup login|margin fetch failed|available margin=' | tail -20" || true)

# Diagnostics that reveal no account data: is the service logging to the journal at all, and
# how many Motilal accounts did the startup login find.
ssh $SSH_OPTS "${DEPLOY_USER}@${DEPLOY_HOST}" \
  "echo \"service log lines (last 10 min): \$(journalctl -u stokr-lite --since '-10 min' --no-pager 2>/dev/null | wc -l)\"; \
   systemctl show stokr-lite -p StandardOutput -p ActiveEnterTimestamp 2>/dev/null; \
   journalctl -u stokr-lite --since '-6 hours' --no-pager 2>/dev/null | grep -o 'MOFSL: startup found.*' | tail -3" || true

if [ -z "$LINES" ]; then
  echo "No MOFSL login/margin lines in the last 6 hours of the stokr-lite log."
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
