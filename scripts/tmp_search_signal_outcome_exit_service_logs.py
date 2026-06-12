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
        "docker logs stokr-api --since 6h 2>&1 | grep -E "
        "'SignalOutcomeExitService|auto-exit-enabled|EXIT_SAFE|signal_outcome|signal.outcome_exit' | tail -120 || true"
    )
    _, stdout, stderr = c.exec_command(cmd)
    out = (stdout.read() + stderr.read()).decode("utf-8", "replace")
    print(out.strip())
    c.close()


if __name__ == '__main__':
    main()

