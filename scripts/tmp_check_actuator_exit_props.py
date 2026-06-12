#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def ssh_run(c: paramiko.SSHClient, cmd: str) -> str:
    _, stdout, stderr = c.exec_command(cmd)
    return (stdout.read() + stderr.read()).decode("utf-8", "replace")


def main() -> None:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)

    cmds = [
        "curl -sf http://127.0.0.1:8080/actuator/env 2>/dev/null | grep -i 'stokr.strategy.exit.auto-exit-enabled\\|stokr.strategy.smart-exit.enabled\\|auto-exit-enabled' | tail -50 || true",
        "curl -sf http://127.0.0.1:8080/actuator/configprops 2>/dev/null | grep -i 'stokr.strategy.exit\\|stokr.strategy.smart-exit' | tail -80 || true",
        "curl -sf http://127.0.0.1:8080/actuator/info 2>/dev/null | tail -20 || true",
    ]

    for cmd in cmds:
        print(">>>", cmd, flush=True)
        out = ssh_run(c, cmd).strip()
        print(out if out else "(no output)", flush=True)
        print("---", flush=True)

    c.close()
    print("done", flush=True)


if __name__ == "__main__":
    main()

