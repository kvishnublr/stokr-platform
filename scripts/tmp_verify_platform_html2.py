#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
PWD = "Temp1234.."

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password=PWD, timeout=30)


def run(cmd, timeout=120):
    print(f"\n=== {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    print(out[-6000:] if len(out) > 6000 else out, flush=True)
    print(f"exit={code}", flush=True)
    return out, code


run("docker exec stokr-api printenv | grep -E '^DB_|^SPRING_DATASOURCE' | head -10")

run(
    """bash -lc 'for db in stokr_platform stokr; do
  STATE=$(docker exec stokr-postgres psql -U postgres -d "$db" -t -c "SELECT state_token FROM platform_broker_oauth_state WHERE vendor_code='"'"'ZERODHA'"'"' ORDER BY created_at DESC LIMIT 1;" 2>/dev/null | tr -d " \\n")
  if [ -n "$STATE" ]; then echo "db=$db state=${STATE:0:12}..."; 
    curl -sI "http://127.0.0.1:8080/api/broker/zerodha/callback?state=$STATE" | grep -iE "HTTP|content-type|location";
    echo BODY:; curl -s "http://127.0.0.1:8080/api/broker/zerodha/callback?state=$STATE" | head -c 600;
    break;
  fi
done'"""
)

run("docker logs stokr-api --since 24h 2>&1 | grep -i 'zerodha.callback' | tail -20")

c.close()
