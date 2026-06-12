while ($true) {
  Start-Sleep -Seconds 300
  Write-Output 'AGENT_LOOP_TICK_exit_monitor {"prompt":"Prod exit monitor tick: run python scripts/exit_monitor_remote.py (or SSH exit_monitor on prod). Report: missing outcome-exit legs, failed logs, API health. If missing legs > 0 run run_outcome_exit_backfill on prod. Summarize only if status changed or ALERT."}'
}
