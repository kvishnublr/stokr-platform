import subprocess
r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A"],
                   input="SELECT active, trigger_source, reason, created_at AT TIME ZONE 'Asia/Kolkata' as ist FROM trading_kill_switch_events ORDER BY created_at DESC LIMIT 3;",
                   capture_output=True, text=True, timeout=10)
print(r.stdout)
