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


# Confirm new code in running container
run("docker exec stokr-api sh -c 'unzip -p /app/stokr-bootstrap.jar BOOT-INF/lib/stokr-user-*.jar' > /tmp/stokr-user.jar 2>/dev/null; unzip -l /tmp/stokr-user.jar 2>/dev/null | grep ZerodhaOAuthCallbackController || jar tf /tmp/stokr-user.jar | grep ZerodhaOAuthCallbackController")
run("strings /tmp/stokr-user.jar 2>/dev/null | grep -F 'return to Telegram' || python3 -c \"import zipfile; z=zipfile.ZipFile('/tmp/stokr-user.jar'); data=b''.join(z.read(n) for n in z.namelist() if n.endswith('.class')); print('return to Telegram' in str(data))\"")

# Find a recent platform oauth state token (consumed or not) for HTML error-path test
run(
    "docker exec stokr-postgres psql -U stokr -d stokr -t -c "
    "\"SELECT state_token FROM platform_broker_oauth_state WHERE vendor_code='ZERODHA' ORDER BY created_at DESC LIMIT 3;\""
)
run(
    "docker exec stokr-postgres psql -U stokr -d stokr -t -c "
    "\"SELECT state_token, consumed, expires_at > now() AS valid FROM platform_broker_oauth_state WHERE vendor_code='ZERODHA' ORDER BY created_at DESC LIMIT 5;\""
)

# Test trader missing-params still redirects
run('curl -sI "http://127.0.0.1:8080/api/broker/zerodha/callback" | grep -iE "HTTP|location|content-type"')

# Test platform state missing token returns HTML (pick first state from query above dynamically in shell)
run(
    r"""STATE=$(docker exec stokr-postgres psql -U stokr -d stokr -t -c "SELECT state_token FROM platform_broker_oauth_state WHERE vendor_code='ZERODHA' ORDER BY created_at DESC LIMIT 1;" | tr -d ' \n');
if [ -n "$STATE" ]; then
  echo "Testing with platform state: ${STATE:0:8}...";
  curl -sI "http://127.0.0.1:8080/api/broker/zerodha/callback?state=$STATE" | grep -iE "HTTP|content-type|location";
  curl -s "http://127.0.0.1:8080/api/broker/zerodha/callback?state=$STATE" | head -c 400;
else
  echo "No platform oauth state found";
fi"""
)

c.close()
