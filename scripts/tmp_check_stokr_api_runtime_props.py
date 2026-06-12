#!/usr/bin/env python3
import paramiko

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

    print("=== docker inspect: stokr-api env (filtered) ===", flush=True)
    # Cmd/Env might include Spring properties via -D or env mapping.
    # We filter in docker inspect output via python locally (simple) by re-invoking with format.
    env_filtered = run(
        c,
        "docker inspect stokr-api --format '{{json .Config.Env}}' | sed 's/\",\"/\\n/g' | grep -E 'STOKR_STRATEGY_.*AUTO_EXIT|STOKR_SMART_EXIT_ENABLED|AUTO_EXIT' || true",
    )
    print(env_filtered.strip())

    print("\n=== stokr-api JVM args (grep auto-exit/smart-exit) ===", flush=True)
    args = run(
        c,
        "docker exec stokr-api sh -lc \"tr '\\\\0' ' ' < /proc/1/cmdline | grep -E 'auto-exit|smart-exit|AUTO_EXIT|SMART_EXIT|STOKR_STRATEGY_AUTO_EXIT|STOKR_SMART_EXIT|stokr\\.strategy\\.exit\\.auto-exit-enabled|stokr\\.strategy\\.smart-exit\\.enabled' || true\"",
    )
    print(args.strip())

    print("\n=== stokr-api application.yml effective properties (actuator configprops, best-effort) ===", flush=True)
    # Actuator endpoints are not guaranteed; curl might be blocked from container context, so use direct curl on host.
    props = run(
        c,
        "curl -sf http://127.0.0.1:8080/actuator/configprops 2>/dev/null | grep -E 'stokr\\.strategy\\.(exit|smart-exit)\\.' | tail -30 || true",
    )
    print(props.strip())

    print("\n=== done ===", flush=True)
    c.close()


if __name__ == "__main__":
    main()

