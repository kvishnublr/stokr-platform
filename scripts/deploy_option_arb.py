"""
Deploy Option Arbitrage Scanner to Stokr server.
Handles both backend (Java) and frontend deployment.
"""
import subprocess
import sys
import time

SERVER = "root@173.249.55.84"
LOCAL_BACKEND = "C:\\Users\\itsvi\\Desktop\\work_new\\stokr-platform\\stokr-lite\\backend\\src\\main\\java\\com\\stokr\\arbitrage"
LOCAL_FRONTEND = "C:\\Users\\itsvi\\Desktop\\work_new\\stokr-platform\\stokr-lite\\frontend"
REMOTE_BACKEND = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage"
REMOTE_FRONTEND = "/opt/stokr/ui"

def run(cmd, check=True, timeout=120):
    print(f"  > {cmd}")
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    if result.stdout.strip():
        print(result.stdout.strip()[:500])
    if result.returncode != 0 and check:
        print(f"  ERROR: {result.stderr.strip()[:500]}")
        return False
    return True

def main():
    print("=" * 60)
    print("  OPTION ARBITRAGE SCANNER DEPLOYMENT")
    print("=" * 60)

    # Step 1: SCP Java files
    print("\n[1/4] Uploading Java files...")
    java_files = [
        "BlackScholesCalculator.java",
        "ArbitrageOpportunity.java",
        "OptionChainService.java",
        "OptionArbitrageController.java",
    ]
    
    # Create remote directory
    run(f"ssh {SERVER} 'mkdir -p {REMOTE_BACKEND}'")
    
    for f in java_files:
        local = f"{LOCAL_BACKEND}\\{f}"
        remote = f"{REMOTE_BACKEND}/{f}"
        if not run(f'scp "{local}" {SERVER}:{remote}'):
            print(f"  FAILED to upload {f}")
            return False
        print(f"  Uploaded {f}")

    # Step 2: SCP updated SecurityConfig
    print("\n[2/4] Uploading SecurityConfig...")
    security_config = "C:\\Users\\itsvi\\Desktop\\work_new\\stokr-platform\\stokr-lite\\backend\\src\\main\\java\\com\\stokr\\config\\SecurityConfig.java"
    run(f'scp "{security_config}" {SERVER}:/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/config/SecurityConfig.java')

    # Step 3: SCP updated App.jsx
    print("\n[3/4] Uploading App.jsx...")
    app_jsx = "C:\\Users\\itsvi\\Desktop\\work_new\\stokr-platform\\stokr-lite\\frontend\\src\\App.jsx"
    run(f'scp "{app_jsx}" {SERVER}:/tmp/App.jsx.new')

    # Step 4: Rebuild backend Docker image
    print("\n[4/4] Rebuilding Docker image (backend)...")
    run(f"ssh {SERVER} 'cd /opt/stokr/stokr-platform/stokr-lite && docker-compose build --no-cache backend 2>&1 | tail -20'", timeout=300)
    
    print("\n[5/5] Restarting backend...")
    run(f"ssh {SERVER} 'docker restart stokr-lite-backend'")
    
    # Wait for backend to start
    print("\nWaiting 15s for backend to start...")
    time.sleep(15)
    
    # Check health
    print("\nChecking backend health...")
    run(f'ssh {SERVER} "curl -s http://localhost:8081/api/option-arbitrage/health | python3 -m json.tool 2>/dev/null || echo Backend not ready yet"')

    print("\n" + "=" * 60)
    print("  DEPLOYMENT COMPLETE")
    print("=" * 60)
    print("\nAccess: https://stokr.in/option-arbitrage")
    print("API: https://stokr.in/api/option-arbitrage/scan")
    print("Health: https://stokr.in/api/option-arbitrage/health")

if __name__ == "__main__":
    main()
