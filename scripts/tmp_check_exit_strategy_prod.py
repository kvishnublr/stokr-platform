#!/usr/bin/env python3
import paramiko
import time

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

    print("=== stokr-api config flags (grep env) ===", flush=True)
    # Spring properties should typically be passed as ENV or JVM args. We check env first.
    env_out = ssh_run(
        c,
        "docker exec stokr-api sh -lc \"printenv | grep -E 'STOKR_STRATEGY_.*AUTO_EXIT|STOKR_SMART_EXIT_ENABLED|STOKR_STRATEGY_AUTO_EXIT_ENABLED|STOKR_STRATEGY_AUTO_EXIT_BREAKEVEN' || true\"",
    )
    print(env_out.strip())

    print("\n=== stokr-api logs: recent exit placement signals ===", flush=True)
    logs = ssh_run(
        c,
        "docker logs stokr-api --since 6h 2>&1 | grep -E 'signal\\.outcome_exit\\.|signal_outcome|signal.outcome_exit|smart_exit\\.|smart_exit.scan|TIME_EXIT|PRESSURE_EXIT|PRESSURE_EXHAUSTION' | tail -120 || true",
    )
    print(logs.strip())

    print("\n=== DB: recent OMS exit orders (idempotencyKey starts with outcome-exit) ===", flush=True)
    dbq = (
        "SELECT created_at, id, execution_mode, symbol, side, quantity, state, strategy_key, idempotency_key "
        "FROM oms_orders "
        "WHERE idempotency_key LIKE 'outcome-exit:%' "
        "ORDER BY created_at DESC "
        "LIMIT 20;"
    )
    db_out = ssh_run(c, f"docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"{dbq}\"")
    print(db_out.strip())

    print("\n=== done ===", flush=True)
    c.close()


if __name__ == "__main__":
    main()

