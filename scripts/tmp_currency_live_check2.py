#!/usr/bin/env python3
import base64
import paramiko

HOST, USER, PWD = "173.249.55.84", "root", "Temp1234.."


def run(cmd, timeout=300):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace").strip()
    c.close()
    return out


def psql(sql):
    b64 = base64.b64encode(sql.encode()).decode()
    return run(
        f"echo {b64} | base64 -d | "
        "docker exec -i stokr-postgres psql -U postgres -d stokr_platform"
    )


if __name__ == "__main__":
    print("=== 6_instances ===")
    print(
        psql(
            """
SELECT sd.strategy_key, si.runtime_state, si.execution_mode, si.enabled
FROM strategy_instances si
JOIN strategy_definitions sd ON sd.id = si.definition_id
WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION')
  AND si.deleted = false;
"""
        )
    )

    print("\n=== nginx_upstream ===")
    print(run("grep -r proxy_pass /etc/nginx/sites-enabled/ 2>/dev/null | head -8"))

    print("\n=== jar_subscription_config ===")
    for container in ["stokr-api", "stokr-api-new"]:
        print(f"--- {container} ---")
        print(
            run(
                f"docker exec {container} sh -c "
                "'unzip -p /app/app.jar BOOT-INF/classes/application-prod.yml 2>/dev/null "
                "| grep -i subscription; "
                "unzip -p /app/app.jar BOOT-INF/classes/application.yml 2>/dev/null "
                "| grep -i subscription' | head -8"
            )
        )

    print("\n=== scan_logs_currency ===")
    for container in ["stokr-api", "stokr-api-new"]:
        print(f"--- {container} ---")
        out = run(
            f"docker logs {container} --since 3h 2>&1 "
            "| grep -iE 'USDINR|EURINR|CDS_MAJOR' | tail -10"
        )
        print(out or "none")

    print("\n=== git_head ===")
    print(run("cd /opt/stokr/stokr-platform && git rev-parse HEAD && git log -1 --oneline"))
