#!/bin/bash
PID=$(systemctl show stokr-lite -p MainPID --value)
echo "PID: $PID"
ls -la /proc/$PID/exe 2>/dev/null
cat /proc/$PID/cmdline 2>/dev/null | tr '\0' ' '
