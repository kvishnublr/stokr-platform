#!/usr/bin/env python3
"""Full reverification of admin dashboard + diagnostics on prod. Read-only."""
import paramiko
import base64
import json

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."

def run(cmd, timeout=120):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out.strip()

def psql(sql):
    b64 = base64.b64encode(sql.encode()).decode()
    return run(f"echo {b64} | base64 -d | docker exec -i stokr-postgres psql -U postgres -d stokr_platform")

sections = []

# 1 Flyway versions
sql_flyway = r"""
\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on
SELECT version, description, success FROM flyway_schema_history
WHERE version IN ('8','008','10','010') OR description ILIKE '%redis%' OR description ILIKE '%auto-detection%' OR description ILIKE '%position_lifecycle%'
ORDER BY installed_rank;
"""
sections.append(("FLYWAY_RELEVANT", psql(sql_flyway)))

# 2 Tables exist?
sql_tables = r"""
\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on
SELECT table_name FROM information_schema.tables
WHERE table_schema='public' AND table_name IN (
  'redis_health_log','market_data_staleness_log','strategy_drift_detection_log',
  'position_orphan_detection_log','position_lifecycle_audit','reconciliation_events'
) ORDER BY 1;
"""
sections.append(("TABLES", psql(sql_tables)))

# 3 Row counts in monitor tables
sql_counts = r"""
\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on
SELECT 'redis_health_log', COUNT(*) FROM redis_health_log
UNION ALL SELECT 'market_data_staleness_log', COUNT(*) FROM market_data_staleness_log
UNION ALL SELECT 'strategy_drift_detection_log', COUNT(*) FROM strategy_drift_detection_log
UNION ALL SELECT 'position_orphan_detection_log', COUNT(*) FROM position_orphan_detection_log
UNION ALL SELECT 'reconciliation_events_today', COUNT(*) FROM reconciliation_events
  WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata')::timestamptz;
"""
sections.append(("ROW_COUNTS", psql(sql_counts)))

# 4 GHOST today
sql_ghost = r"""
\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on
SELECT discrepancy_type, symbol, broker_qty, internal_qty, status, COUNT(*)
FROM reconciliation_events
WHERE created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata')::timestamptz
GROUP BY 1,2,3,4,5 ORDER BY 6 DESC LIMIT 15;
"""
sections.append(("RECON_TODAY", psql(sql_ghost)))

# 5 signal_id on LIVE fills
sql_sig = r"""
\pset format unaligned
\pset fieldsep '|'
\pset tuples_only on
SELECT COUNT(*) FILTER (WHERE signal_id IS NULL) as null_signal,
       COUNT(*) FILTER (WHERE signal_id IS NOT NULL) as has_signal,
       COUNT(*) as total
FROM oms_orders
WHERE pipeline_mode='LIVE' AND state='FILLED'
  AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata')::timestamptz;
"""
sections.append(("LIVE_SIGNAL_ID", psql(sql_sig)))

# 6 API jar classes - io.stokr in deployed container
sections.append(("JAR_IO_STOKR", run(
    "docker exec stokr-api sh -c 'jar tf /app/app.jar 2>/dev/null | grep -E \"io/stokr/bootstrap/controller/Admin|AdminHealthDashboard\" | head -20' || "
    "docker exec stokr-api sh -c 'find /app -name \"*.jar\" 2>/dev/null | head -3'"
)))

# 7 HTTP probes (localhost inside server)
for path in [
    "/admin/dashboard",
    "/admin-dashboard.html",
    "/api/admin/diagnostics/health",
    "/api/admin/diagnostics/quick-summary",
    "/api/admin/operations/diagnostics",
]:
    sections.append((f"HTTP_{path}", run(
        f"docker exec stokr-api sh -c 'wget -qO- --server-response http://127.0.0.1:8080{path} 2>&1 | head -15' 2>/dev/null || "
        f"curl -s -o /dev/null -w '%{{http_code}}' http://127.0.0.1:8080{path} 2>/dev/null || echo curl_failed"
    )))

# 8 Git on prod
sections.append(("PROD_GIT", run(
    "cd /opt/stokr/stokr-platform && git log -1 --oneline && git branch --show-current"
)))

# 9 Static file in container
sections.append(("STATIC_HTML", run(
    "docker exec stokr-api sh -c 'ls -la /app/admin-dashboard.html 2>&1; ls -la /app/BOOT-INF/classes/static/admin-dashboard.html 2>&1' | head -10"
)))

if __name__ == "__main__":
    for name, body in sections:
        print(f"\n{'='*60}\n=== {name} ===\n{body}")
