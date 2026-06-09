#!/usr/bin/env python3
"""Deploy Nature Organic UI + CORS fix to production."""
import os
import paramiko
from pathlib import Path

HOST = "173.249.55.84"
USER = "root"
PW = os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "nature-organic-ui"
REMOTE = "/var/www/stokr/new"


def run(ssh, cmd, timeout=120):
    _, o, e = ssh.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    return out


def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PW, timeout=30)
    sftp = ssh.open_sftp()

    print("=== Ensure remote dirs ===")
    run(ssh, f"mkdir -p {REMOTE}/trader {REMOTE}/admin {REMOTE}/shared")

    uploads = [
        (UI / "shared" / "stokr-panel.js", f"{REMOTE}/shared/stokr-panel.js"),
        (UI / "trader" / "index.html", f"{REMOTE}/trader/index.html"),
        (UI / "admin" / "index.html", f"{REMOTE}/admin/index.html"),
    ]
    for local, remote in uploads:
        sftp.put(str(local), remote)
        print("uploaded", local.name, "->", remote)

    sftp.close()

    print("\n=== Update CORS for 8082/8083 ===")
    cors_patch = r"""
python3 - <<'PY'
from pathlib import Path
p = Path('/opt/stokr/stokr-platform/.env')
text = p.read_text()
extra = ['http://173.249.55.84:8082', 'http://173.249.55.84:8083']
for origin in extra:
    if origin not in text:
        if 'STOKR_CORS_ALLOWED_ORIGINS=' in text:
            lines = []
            for line in text.splitlines():
                if line.startswith('STOKR_CORS_ALLOWED_ORIGINS='):
                    val = line.split('=',1)[1]
                    if origin not in val:
                        val = val + ',' + origin
                    line = 'STOKR_CORS_ALLOWED_ORIGINS=' + val
                lines.append(line)
            text = '\n'.join(lines) + ('\n' if text.endswith('\n') else '')
        else:
            text += f'\nSTOKR_CORS_ALLOWED_ORIGINS={origin}\n'
p.write_text(text)
print('CORS updated')
PY
"""
    print(run(ssh, cors_patch))

    print("\n=== Restart API for CORS ===")
    print(run(ssh, "cd /opt/stokr/stokr-platform && docker compose up -d stokr-api"))
    print(run(ssh, "sleep 8 && curl -s http://127.0.0.1:8080/actuator/health | head -c 200"))

    print("\n=== Verify static assets ===")
    for port, path in [(8082, "/index.html"), (8083, "/index.html"), (8082, "/../shared/stokr-panel.js")]:
        url = f"http://127.0.0.1:{port}{path.replace('/../shared/', '/shared/') if 'shared' in path else path}"
        if "shared" in path:
            url = "http://127.0.0.1:8082/../shared/stokr-panel.js"
        # python http.server from trader dir can't serve ../shared - need separate server or copy js into trader dir
        pass

    # Copy shared js into trader/admin dirs for python http.server compatibility
    run(ssh, f"cp {REMOTE}/shared/stokr-panel.js {REMOTE}/trader/stokr-panel.js")
    run(ssh, f"cp {REMOTE}/shared/stokr-panel.js {REMOTE}/admin/stokr-panel.js")

    print("\n=== Patch HTML script src to local copy ===")
    run(ssh, f"sed -i 's|../shared/stokr-panel.js|stokr-panel.js|g' {REMOTE}/trader/index.html {REMOTE}/admin/index.html")

    print("\n=== Restart python static servers ===")
    run(ssh, "pkill -f 'python3 -m http.server 8082' || true")
    run(ssh, "pkill -f 'python3 -m http.server 8083' || true")
    run(ssh, f"cd {REMOTE}/trader && nohup python3 -m http.server 8082 > /var/log/stokr/new/trader.log 2>&1 &")
    run(ssh, f"cd {REMOTE}/admin && nohup python3 -m http.server 8083 > /var/log/stokr/new/admin.log 2>&1 &")
    run(ssh, "sleep 2")

    print(run(ssh, "curl -s -o /dev/null -w 'trader:%{http_code}\\n' http://127.0.0.1:8082/index.html"))
    print(run(ssh, "curl -s -o /dev/null -w 'admin:%{http_code}\\n' http://127.0.0.1:8083/index.html"))
    print(run(ssh, "curl -s -o /dev/null -w 'js:%{http_code}\\n' http://127.0.0.1:8082/stokr-panel.js"))
    print(run(ssh, "docker exec stokr-api printenv STOKR_CORS_ALLOWED_ORIGINS"))

    ssh.close()
    print("\nDeploy complete.")


if __name__ == "__main__":
    main()
