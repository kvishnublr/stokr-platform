import subprocess, json, sys
from datetime import datetime

def ssh(cmd):
    r = subprocess.run(["ssh", "root@173.249.55.84", cmd], capture_output=True, text=True, timeout=20)
    return r.stdout.strip()

# Write a shell script to the server and execute it
shell_script = """
#!/bin/bash
echo "=== Instrument Data ==="
for u in NIFTY BANKNIFTY MIDCPNIFTY FINNIFTY; do
    echo "--- $u ---"
    grep "${u}" /tmp/instruments.csv | grep "FUT" | grep "NFO" | head -1
    echo "Options count:"
    grep "${u}" /tmp/instruments.csv | grep "OPT" | grep "NFO" | wc -l
    echo "Expiries:"
    grep "${u}" /tmp/instruments.csv | grep "OPT" | grep "NFO" | cut -d',' -f6 | sort -u
done
"""

# Write to server
r = subprocess.run(["ssh", "root@173.249.55.84", f"cat > /tmp/check_inst.sh << 'HEREDOC'\n{shell_script}\nHEREDOC\nchmod +x /tmp/check_inst.sh"],
    capture_output=True, text=True, timeout=15)

result = ssh("bash /tmp/check_inst.sh")
print(result)

# Also check FINNIFTY/NIFTY expiry days
for d in ["2026-07-21", "2026-07-28", "2026-08-25"]:
    dt = datetime.strptime(d, "%Y-%m-%d")
    print(f"  {d} = {dt.strftime('%A')}")
