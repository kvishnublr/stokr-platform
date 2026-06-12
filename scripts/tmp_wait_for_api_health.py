#!/usr/bin/env python3
import paramiko
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run_ssh(cmd: str) -> str:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, stdout, stderr = c.exec_command(cmd)
    out = (stdout.read() + stderr.read()).decode("utf-8", "replace")
    c.close()
    return out


def main() -> None:
    print("waiting 30s...", flush=True)
    time.sleep(30)

    print("\n=== health code ===")
    print(run_ssh("curl -sS -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:8080/actuator/health || echo FAIL").strip())

    print("\n=== api restart/health state ===")
    print(
        run_ssh(
            "docker inspect -f '{{.Name}} RestartCount={{.RestartCount}} State={{.State.Status}} ExitCode={{.State.ExitCode}} "
            "Health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' stokr-api"
        ).strip()
    )

    print("\n=== api log grep for postgres/host errors (max 60 lines) ===")
    print(run_ssh("docker logs stokr-api --since 45m --tail 250 2>&1 | rg -i -m 60 'UnknownHostException|postgres'").strip())

    print("\n=== done ===")


if __name__ == "__main__":
    main()

