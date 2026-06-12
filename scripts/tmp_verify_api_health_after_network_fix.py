#!/usr/bin/env python3
import paramiko

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
    print("=== stokr-api restart/health state ===")
    print(
        run_ssh(
            "docker inspect -f '{{.Name}} RestartCount={{.RestartCount}} State={{.State.Status}} ExitCode={{.State.ExitCode}} "
            "Health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} StartedAt={{.State.StartedAt}} FinishedAt={{.State.FinishedAt}}' stokr-api"
        ).strip()
    )

    print("\n=== stokr-api port check ===")
    print(run_ssh("curl -sS -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:8080/actuator/health || echo FAIL").strip())

    print("\n=== last 80 lines api logs (look for db host errors) ===")
    print(
        run_ssh("docker logs stokr-api --since 30m --tail 120 2>&1 | python -c \"import sys,re;lines=sys.stdin.read().splitlines();\nprint('\\n'.join([ln for ln in lines if any(w in ln for w in ['UnknownHostException','postgres','exception','ERROR','Unable','Failed','exit','stack_trace'])][-80:]))\"").strip()
    )

    print("\n=== done ===")


if __name__ == "__main__":
    main()

