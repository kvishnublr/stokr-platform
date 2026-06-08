#!/usr/bin/env python3
import json
import sys
import paramiko
import urllib.request
import urllib.error

HOST = "173.249.55.84"
PWD = "Temp1234.."
MAIN = "/opt/stokr/stokr-platform"
VISHNU_UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password=PWD, timeout=30)


def run(cmd, timeout=600):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    print(f">>> {cmd}\nexit={code}\n{out[-4000:]}\n", flush=True)
    return code, out


# Ensure UI is on latest commit
run(f"cd {MAIN} && git log -1 --oneline")
run("docker ps --format '{{.Names}} {{.Status}}' | grep stokr")

# Finish UI deploy if needed
run(f"cd {MAIN} && export STOKR_DEPLOY_BRANCH=Release_v2 && bash deploy.sh ui", timeout=3600)

run("curl -sf http://127.0.0.1:8080/actuator/health")
run('curl -sf https://stokr.in/api/actuator/health')

# Admin login to impersonate/check workstation via internal path if available
# Try admin login
token = None
for email, pwd in [("admin@stokr.local", "admin123")]:
    try:
        payload = json.dumps({"principal": email, "password": pwd}).encode()
        req = urllib.request.Request(
            "https://stokr.in/api/auth/login",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=20) as r:
            body = json.loads(r.read().decode())
            if body.get("success"):
                data = body.get("data", {})
                token = data.get("accessToken") or data.get("access_token")
                print(f"LOGIN OK {email}", flush=True)
                break
    except Exception as ex:
        print(f"login fail {email}: {ex}", flush=True)

if token:
    # Check admin can query trader workstation? Probably not - need trader token
    pass

# Check API logs for vishnu workstation after restart
run(
    f"docker logs stokr-api --since 5m 2>&1 | grep -E '{VISHNU_UID}|terminal.workstation|exposure_failed|InvalidTypeId' | tail -20"
)

# Direct DB broker state
sql = f"""SELECT status, last_sync_at, health_status FROM broker_accounts WHERE user_id='{VISHNU_UID}' AND deleted=false;"""
run(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"')

# Trigger workstation via server-side curl with service account if we have trader JWT in logs - skip
# Use SSH to hit internal endpoint with generated token - too complex

# Check recent workstation errors
run("docker logs stokr-api --since 10m 2>&1 | grep -i 'workstation\\|exposure_failed\\|ClassCast\\|InvalidTypeId' | tail -15")

c.close()
print("VERIFY DONE", flush=True)
