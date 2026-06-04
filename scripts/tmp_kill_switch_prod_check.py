#!/usr/bin/env python3
"""One-off prod check: kill switch, blocked orders, open LIVE positions."""
import json
import paramiko

HOST, USER, PWD = "173.249.55.84", "root", "Temp1234.."
PG = "docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A"


def run(client, cmd, timeout=120):
    print(f"\n>>> {cmd[:200]}")
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode(errors="replace").strip()
    err = stderr.read().decode(errors="replace").strip()
    if out:
        print(out)
    if err:
        print("STDERR:", err[:800])
    return out


def main():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PWD, timeout=30)

    run(client, "date; TZ=Asia/Kolkata date")
    run(client, "docker exec stokr-redis redis-cli GET stokr:kill:switch")
    run(client, "docker exec stokr-redis redis-cli GET stokr:live:armed")
    run(
        client,
        PG
        + " -c \"SELECT active, trigger_source, left(reason,80), created_at AT TIME ZONE 'Asia/Kolkata' FROM trading_kill_switch_events ORDER BY created_at DESC LIMIT 5\"",
    )
    run(
        client,
        PG
        + " -c \"SELECT count(*) FROM oms_safety_blocked_orders WHERE created_at >= CURRENT_DATE AND upper(reason) LIKE '%KILL%'\"",
    )
    run(
        client,
        PG
        + " -c \"SELECT left(reason,60), count(*) FROM oms_safety_blocked_orders WHERE created_at >= CURRENT_DATE GROUP BY 1 ORDER BY 2 DESC LIMIT 12\"",
    )
    run(
        client,
        PG
        + " -c \"SELECT state, count(*) FROM oms_orders WHERE deleted=false AND execution_mode='LIVE' AND state IN ('OPEN','PENDING','PARTIALLY_FILLED','SUBMITTED') GROUP BY 1\"",
    )
    run(
        client,
        PG
        + " -c \"SELECT count(*) FROM oms_orders WHERE deleted=false AND execution_mode='LIVE' AND created_at >= CURRENT_DATE\"",
    )
    run(
        client,
        'docker logs stokr-api 2>&1 | grep -E "kill_switch|market_open.disarm|platform.recovery|DEACTIVATE_KILL" | tail -30',
    )

    token_out = run(
        client,
        'curl -sf -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" '
        '-d \'{"principal":"admin","password":"password"}\'',
    )
    token = ""
    try:
        token = json.loads(token_out).get("data", {}).get("accessToken", "")
    except Exception as e:
        print("token parse error", e)

    if token:
        for ep in [
            "operations/snapshot",
            "oms/kill-switch/status",
            "oms/diagnostics",
        ]:
            cmd = (
                f'curl -sf -H "Authorization: Bearer {token}" '
                f'"http://localhost:8080/api/admin/{ep}"'
            )
            raw = run(client, cmd)
            if raw:
                try:
                    d = json.loads(raw).get("data", json.loads(raw))
                    if ep == "operations/snapshot":
                        sys = d.get("system") or {}
                        fresh = d.get("marketFreshness") or {}
                        print(
                            "SNAPSHOT: killSwitch=%s liveArmed=%s freshStatus=%s lag1m=%s"
                            % (
                                sys.get("killSwitch"),
                                sys.get("liveTradingArmed"),
                                fresh.get("status"),
                                fresh.get("latest1mLagSeconds"),
                            )
                        )
                    elif "kill-switch" in ep:
                        print("KILL_API:", json.dumps(d, indent=2)[:2000])
                    else:
                        ks = d.get("killSwitch") or {}
                        broker = (d.get("brokerConnection") or {})
                        print(
                            "OMS_DIAG: killActive=%s liveBlocked=%s globalHalt=%s"
                            % (
                                ks.get("active"),
                                broker.get("liveOrdersBlocked"),
                                broker.get("globalHalt"),
                            )
                        )
                except Exception as ex:
                    print("json err", ex)

    run(
        client,
        f"cd /opt/stokr/stokr-platform && git rev-parse --short HEAD && git log -1 --oneline",
    )
    run(
        client,
        "docker logs stokr-api 2>&1 | grep -E 'platform.recovery' | tail -15",
    )
    client.close()


if __name__ == "__main__":
    main()
