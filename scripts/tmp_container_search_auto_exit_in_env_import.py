#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def main() -> None:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)

    cmd = (
        "docker exec stokr-api sh -lc \""
        "ls -la /app/.. 2>/dev/null | head -30; "
        "echo '---'; "
        "ls -la /app/../.env* 2>/dev/null || true; "
        "echo '---'; "
        "grep -n -E 'AUTO_EXIT|auto-exit|smart-exit' /app/../.env* 2>/dev/null || true; "
        "\""
    )
    _, stdout, stderr = c.exec_command(cmd)
    out = (stdout.read() + stderr.read()).decode("utf-8", "replace")
    print(out.strip())
    c.close()


if __name__ == "__main__":
    main()

