#!/usr/bin/env python3
import paramiko
import json

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run(c: paramiko.SSHClient, cmd: str) -> str:
    _, stdout, stderr = c.exec_command(cmd)
    return (stdout.read() + stderr.read()).decode("utf-8", "replace")


def main() -> None:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)

    print("=== docker inspect: stokr-api Config.Env (filtered) ===", flush=True)
    env_out = run(
        c,
        "docker inspect stokr-api --format '{{json .Config.Env}}' | tr -d '[]' | tr ',' '\\n' | grep -i -E 'STOKR_STRATEGY_EXIT|STOKR_STRATEGY_AUTO_EXIT|AUTO_EXIT|SMART_EXIT|stokr\\.strategy\\.exit' || true",
    ).strip()
    print(env_out if env_out else "(none matched)", flush=True)

    print("\n=== docker inspect: stokr-api Config.Cmd ===", flush=True)
    print(run(c, "docker inspect stokr-api --format '{{json .Config.Cmd}}'").strip(), flush=True)

    print("\n=== docker inspect: stokr-api java system props from Cmd (filtered) ===", flush=True)
    cmd_out = run(
        c,
        "docker inspect stokr-api --format '{{json .Config.Cmd}}' | grep -i -E 'auto-exit|SMART_EXIT|AUTO_EXIT|stokr\\.strategy\\.exit|smart-exit' || true",
    ).strip()
    print(cmd_out if cmd_out else "(none matched)", flush=True)

    c.close()
    print("\n=== done ===", flush=True)


if __name__ == "__main__":
    main()

