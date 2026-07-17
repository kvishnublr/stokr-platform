import subprocess
import time

DEPLOY_DIR = "C:/Users/itsvi/Desktop/work_new/stokr-platform/scripts/deploy"
SERVER = "root@173.249.55.84"
BACKEND_SRC = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage"
FRONTEND_SRC = "/opt/stokr/stokr-platform/stokr-lite/frontend/src/pages"

def scp(local, remote):
    r = subprocess.run(["scp", local, f"{SERVER}:{remote}"], capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        print(f"  SCP FAILED: {r.stderr.strip()}")
        return False
    return True

def ssh(cmd, timeout=60):
    r = subprocess.run(["ssh", SERVER, cmd], capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip() + r.stderr.strip()

print("=" * 60)
print("DEPLOYING: MIDCPNIFTY/FINNIFTY + Calendar Spread + Vol Surface")
print("=" * 60)

# Step 1: Upload Java files
print("\n1. Uploading Java files...")
files = {
    f"{DEPLOY_DIR}/OptionChainService.java": f"{BACKEND_SRC}/OptionChainService.java",
    f"{DEPLOY_DIR}/OptionArbitrageController.java": f"{BACKEND_SRC}/OptionArbitrageController.java",
    f"{DEPLOY_DIR}/CalendarSpreadService.java": f"{BACKEND_SRC}/CalendarSpreadService.java",
    f"{DEPLOY_DIR}/VolSurfaceService.java": f"{BACKEND_SRC}/VolSurfaceService.java",
}
for local, remote in files.items():
    print(f"  {local.split('/')[-1]} -> {remote.split('/')[-1]}")
    if not scp(local, remote):
        print("  ABORT: SCP failed")
        exit(1)

# Step 2: Upload frontend
print("\n2. Uploading frontend...")
if not scp(f"{DEPLOY_DIR}/OptionArbitrage.jsx", f"{FRONTEND_SRC}/OptionArbitrage.jsx"):
    print("  ABORT: Frontend SCP failed")
    exit(1)

# Step 3: Build JAR
print("\n3. Building JAR on server...")
build_out = ssh(f"cd /opt/stokr/stokr-platform/stokr-lite/backend && mvn clean package -DskipTests 2>&1 | tail -8", timeout=180)
print(f"  {build_out}")

if "BUILD SUCCESS" not in build_out:
    print("  ABORT: Build failed")
    exit(1)

# Step 4: Build Docker image
print("\n4. Building Docker image...")
docker_out = ssh(f"cd /opt/stokr/stokr-platform/stokr-lite && docker compose build --no-cache backend 2>&1 | tail -5", timeout=300)
print(f"  {docker_out}")

if "DONE" not in docker_out and "Built" not in docker_out:
    print("  ABORT: Docker build failed")
    exit(1)

# Step 5: Restart container
print("\n5. Restarting backend container...")
restart_out = ssh(f"cd /opt/stokr/stokr-platform/stokr-lite && docker compose up -d --force-recreate --no-deps backend 2>&1")
print(f"  {restart_out}")

# Step 6: Wait and verify
print("\n6. Waiting for startup...")
time.sleep(25)

# Check health
health = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/health'")
print(f"  Health: {health}")

# Check for errors
errors = ssh("docker logs stokr-lite-backend 2>&1 | grep -E 'ERROR|Exception|failed' | tail -5")
if errors:
    print(f"  ERRORS: {errors}")
else:
    print("  No errors in logs")

# Quick test scan
print("\n7. Testing NIFTY scan...")
scan = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/scan?underlying=NIFTY' | python3 -c 'import sys,json; d=json.load(sys.stdin); print(f\"Status: {d.get(\\\"status\\\")}, Opps: {d.get(\\\"totalOpportunities\\\", 0)}\")' 2>/dev/null")
print(f"  {scan}")

print("\n8. Testing vol-surface...")
vol = ssh("curl -s 'http://localhost:8081/api/option-arbitrage/vol-surface?underlying=NIFTY' | python3 -c 'import sys,json; d=json.load(sys.stdin); s=d.get(\\\"summary\\\",{}); print(f\"Status: {d.get(\\\"status\\\")}, Weekly IV: {s.get(\\\"avgWeeklyIV\\\")}%, Monthly IV: {s.get(\\\"avgMonthlyIV\\\")}%\")' 2>/dev/null")
print(f"  {vol}")

print("\n" + "=" * 60)
print("DEPLOYMENT COMPLETE")
print("=" * 60)
