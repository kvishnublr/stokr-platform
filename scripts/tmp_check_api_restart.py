#!/usr/bin/env python3
import paramiko
import re


HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run_ssh(client: paramiko.SSHClient, cmd: str) -> str:
    _, stdout, stderr = client.exec_command(cmd)
    out = (stdout.read() + stderr.read()).decode("utf-8", "replace")
    return out


def main() -> None:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)

    print("=== stokr-api container status ===", flush=True)
    print(run_ssh(c, "docker ps --filter name=stokr-api --format '{{.Names}} {{.Status}} {{.Image}}'").strip())

    print("\n=== stokr-api restart/health state ===", flush=True)
    inspect = run_ssh(
        c,
        "docker inspect -f '{{.Name}} RestartCount={{.RestartCount}} State={{.State.Status}} ExitCode={{.State.ExitCode}} "
        "Health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} StartedAt={{.State.StartedAt}} FinishedAt={{.State.FinishedAt}}' stokr-api",
    ).strip()
    print(inspect if inspect else "(no output)")

    print("\n=== stokr-api recent log lines (last 2h) ===", flush=True)
    logs = run_ssh(c, "docker logs stokr-api --since 2h --tail 250 2>&1 || true").strip()
    print(logs)

    # Lightweight error fingerprinting (best-effort)
    lines = logs.splitlines()
    pats = [
        re.compile(r"Exception"),
        re.compile(r"\bERROR\b", re.IGNORECASE),
        re.compile(r"OutOfMemory", re.IGNORECASE),
        re.compile(r"InvalidTypeId", re.IGNORECASE),
        re.compile(r"ClassCastException", re.IGNORECASE),
        re.compile(r"NullPointerException", re.IGNORECASE),
        re.compile(r"500 Internal", re.IGNORECASE),
        re.compile(r"Unhandled", re.IGNORECASE),
    ]
    err_lines = [ln for ln in lines if any(p.search(ln) for p in pats)]
    if err_lines:
        print("\n--- error-like lines (dedup not applied, last 40) ---", flush=True)
        for ln in err_lines[-40:]:
            print(ln)
    else:
        print("\n(no obvious exception/error patterns found in the last 250 log lines)", flush=True)

    c.close()
    print("\n=== done ===", flush=True)


if __name__ == "__main__":
    main()

