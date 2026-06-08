#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
ENV_PATH = "/opt/stokr/stokr-platform/.env"


def main() -> None:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)

    cmds = [
        f"ls -la {ENV_PATH} || true",
        f"grep -n -E 'AUTO_EXIT|auto-exit|SMART_EXIT|smart-exit' {ENV_PATH} 2>/dev/null || true",
    ]
    for cmd in cmds:
        _, stdout, stderr = c.exec_command(cmd)
        out = (stdout.read() + stderr.read()).decode("utf-8", "replace").strip()
        print('>>>', cmd)
        print(out if out else '(none)')
    c.close()


if __name__ == "__main__":
    main()

