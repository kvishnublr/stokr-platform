import subprocess, sys

SERVER = "root@173.249.55.84"
LOCAL_JAR_DIR = "C:/Users/itsvi/Desktop/work_new/stokr-platform/stokr-lite/backend/target"
SERVER_BACKEND_DIR = "/opt/stokr/stokr-platform/stokr-lite/backend"
SERVER_UI_DIR = "/opt/stokr/ui"
LOCAL_UI_DIST = "C:/Users/itsvi/Desktop/work_new/stokr-platform/stokr-lite/frontend/dist"

def run(cmd, check=True):
    print(f"  > {cmd}")
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.stdout.strip(): print(r.stdout.strip())
    if r.stderr.strip(): print(r.stderr.strip())
    if check and r.returncode != 0:
        print(f"FAILED (exit {r.returncode})")
        sys.exit(1)
    return r

print("="*60)
print("DEPLOY: Multi-strike + Mid-price + Time-filter + Hold-to-expiry")
print("="*60)

# Step 1: Run DB updates
print("\n[1] Updating DB settings...")
run(f'scp "C:/Users/itsvi/Desktop/work_new/stokr-platform/stokr-lite/patches/update_settings_for_improvements.sql" {SERVER}:/tmp/')
run(f'ssh {SERVER} "psql -h localhost -U postgres -d stokr_lite -f /tmp/update_settings_for_improvements.sql"')

# Step 2: Verify DB
print("\n[2] Verifying DB settings...")
run(f'ssh {SERVER} "psql -h localhost -U postgres -d stokr_lite -c \\\"SELECT setting_key, setting_value FROM option_arb_auto_exec_settings WHERE setting_key IN (\'max_positions_per_underlying\', \'max_total_positions\', \'time_filter_enabled\', \'auto_execute_enabled\');\\\""')

# Step 3: Push code to server
print("\n[3] Pushing code to server...")
run("git add -A")
run("git commit -m \"Multi-strike(3x) + Mid-price entry + Peak-window filter + Hold-to-expiry\"")
run("git push")

print("\n[4] Building Docker image on server (no-cache)...")
run(f'ssh {SERVER} "cd {SERVER_BACKEND_DIR} && DOCKER_BUILDKIT=0 docker build --no-cache -t stokr-lite-backend:latest . 2>&1 | tail -5"')

print("\n[5] Killing rogue Java processes...")
run(f'ssh {SERVER} "fuser -k 8081/tcp 2>/dev/null; sleep 1"')

print("\n[6] Restarting Docker container...")
run(f'ssh {SERVER} "docker stop stokr-lite-backend 2>/dev/null; docker rm stokr-lite-backend 2>/dev/null"')
run(f'ssh {SERVER} """docker run -d --name stokr-lite-backend --restart unless-stopped --env-file /opt/stokr/stokr-platform/stokr-lite/.env -p 8081:8080 --add-host host.docker.internal:host-gateway --network stokr-lite_stokr-net stokr-lite-backend:latest"""')

print("\n[7] Waiting for startup...")
import time
time.sleep(10)

print("\n[8] Checking health...")
r = run(f'ssh {SERVER} "curl -s http://localhost:8081/api/option-arbitrage/health"', check=False)
print(r.stdout)

print("\n[9] Checking logs for errors...")
run(f'ssh {SERVER} "docker logs stokr-lite-backend 2>&1 | tail -20"')

print("\n" + "="*60)
print("DEPLOY COMPLETE")
print("="*60)
print()
print("Changes deployed:")
print("  1. Multi-strike: 3 positions per underlying (was 1)")
print("  2. Mid-price: LIMIT at (bid+ask)/2 (was ask/bid)")
print("  3. Time filter: only enter 09:15-09:45, 14:00-15:00")
print("  4. Hold-to-expiry: ATM held to 15:28 (was 15:25)")
print()
print("Schedule:")
print("  09:16-09:45  Auto-execute peak window 1 (opening chaos)")
print("  14:00-15:00  Auto-execute peak window 2 (pre-close)")
print("  15:20        Exit non-ATM near-expiry positions")
print("  15:28        Exit ALL remaining positions")
print("  15:25        Roll monitor checks for better strikes")
