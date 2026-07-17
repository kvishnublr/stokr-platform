import subprocess
import re

def ssh_cmd(cmd):
    result = subprocess.run(["ssh", "root@173.249.55.84", cmd], capture_output=True, text=True, timeout=30)
    return result.stdout + result.stderr

def scp_from(remote, local):
    subprocess.run(["scp", f"root@173.249.55.84:{remote}", local], check=True, timeout=30)

def scp_to(local, remote):
    subprocess.run(["scp", local, f"root@173.249.55.84:{remote}"], check=True, timeout=30)

# ============================================================
# FIX 1: Futures validation + health endpoint
# ============================================================
print("=== FIX 1: Futures Validation + Health ===")

BASE = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage"
LOCAL_DIR = "C:/Users/itsvi/Desktop/work_new/stokr-platform/scripts"

scp_from(f"{BASE}/OptionArbitrageController.java", f"{LOCAL_DIR}/OptionArbitrageController.java")

with open(f"{LOCAL_DIR}/OptionArbitrageController.java", "r") as f:
    c = f.read()

# Fix NIFTY futures validation
c = c.replace(
    'double niftyFut = spotFetcher.getSpotPrice("NFO:NIFTY26JULFUT");\n                double futPremiumEstimate = niftySpot * (Math.exp(RISK_FREE_RATE * 7.0 / 365.0) - 1.0);\n                if (niftyFut <= 0 || Math.abs(niftyFut - niftySpot) < futPremiumEstimate * 0.5) {\n                    niftyFut = niftySpot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);\n                }',
    'double niftyFut = spotFetcher.getSpotPrice("NFO:NIFTY26JULFUT");\n                double futPremiumEstimate = niftySpot * (Math.exp(RISK_FREE_RATE * 7.0 / 365.0) - 1.0);\n                double expectedFutLow = niftySpot - futPremiumEstimate * 3;\n                double expectedFutHigh = niftySpot + futPremiumEstimate * 5;\n                if (niftyFut <= 0 || niftyFut < expectedFutLow || niftyFut > expectedFutHigh) {\n                    log.warn("NIFTY futures {} outside expected range [{}, {}], using synthetic", niftyFut, expectedFutLow, expectedFutHigh);\n                    niftyFut = niftySpot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);\n                }')

# Fix BANKNIFTY futures validation
c = c.replace(
    'double bankNiftyFut = spotFetcher.getSpotPrice("NFO:BANKNIFTY26JULFUT");\n                double bankFutPremiumEstimate = bankNiftySpot * (Math.exp(RISK_FREE_RATE * 7.0 / 365.0) - 1.0);\n                if (bankNiftyFut <= 0 || Math.abs(bankNiftyFut - bankNiftySpot) < bankFutPremiumEstimate * 0.5) {\n                    bankNiftyFut = bankNiftySpot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);\n                }',
    'double bankNiftyFut = spotFetcher.getSpotPrice("NFO:BANKNIFTY26JULFUT");\n                double bankFutPremiumEstimate = bankNiftySpot * (Math.exp(RISK_FREE_RATE * 7.0 / 365.0) - 1.0);\n                double bankExpectedFutLow = bankNiftySpot - bankFutPremiumEstimate * 3;\n                double bankExpectedFutHigh = bankNiftySpot + bankFutPremiumEstimate * 5;\n                if (bankNiftyFut <= 0 || bankNiftyFut < bankExpectedFutLow || bankNiftyFut > bankExpectedFutHigh) {\n                    log.warn("BANKNIFTY futures {} outside expected range [{}, {}], using synthetic", bankNiftyFut, bankExpectedFutLow, bankExpectedFutHigh);\n                    bankNiftyFut = bankNiftySpot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);\n                }')

# Fix health endpoint
c = c.replace('"minEdgeAfterCosts", 200', '"minEdgeAfterCosts", 300')

with open(f"{LOCAL_DIR}/OptionArbitrageController.java", "w") as f:
    f.write(c)

scp_to(f"{LOCAL_DIR}/OptionArbitrageController.java", f"{BASE}/OptionArbitrageController.java")
print("  Uploaded OptionArbitrageController.java")

# Verify
v = ssh_cmd(f"grep -c 'expectedFutLow' {BASE}/OptionArbitrageController.java")
print(f"  Verification: {v.strip()} replacements found")


# ============================================================
# FIX 2: Cap magnitude in AnomalyDetectionService.java
# ============================================================
print("\n=== FIX 2: Anomaly Magnitude Capping ===")

TICK_BASE = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/marketdata/tick"
scp_from(f"{TICK_BASE}/AnomalyDetectionService.java", f"{LOCAL_DIR}/AnomalyDetectionService.java")

with open(f"{LOCAL_DIR}/AnomalyDetectionService.java", "r") as f:
    c = f.read()

# Add MAX constants before recordAnomaly and cap values
old_record = '    private void recordAnomaly(String symbol, String type, BigDecimal price, BigDecimal magnitude,\n                                long volume, BigDecimal vwapDev, String direction) {\n        var anomaly = TickAnomaly.builder()'

new_record = '    private static final BigDecimal MAX_MAGNITUDE = new BigDecimal("9999999999.9999");\n    private static final BigDecimal MAX_VWAP_DEV = new BigDecimal("9999.9999");\n\n    private void recordAnomaly(String symbol, String type, BigDecimal price, BigDecimal magnitude,\n                                long volume, BigDecimal vwapDev, String direction) {\n        if (magnitude == null || magnitude.compareTo(MAX_MAGNITUDE) > 0) {\n            magnitude = MAX_MAGNITUDE;\n        }\n        if (vwapDev != null && vwapDev.abs().compareTo(MAX_VWAP_DEV) > 0) {\n            vwapDev = vwapDev.signum() >= 0 ? MAX_VWAP_DEV : MAX_VWAP_DEV.negate();\n        }\n        var anomaly = TickAnomaly.builder()'

if old_record in c:
    c = c.replace(old_record, new_record)
    print("  Magnitude capping added")
else:
    print("  WARNING: recordAnomaly pattern not found")

with open(f"{LOCAL_DIR}/AnomalyDetectionService.java", "w") as f:
    f.write(c)

scp_to(f"{LOCAL_DIR}/AnomalyDetectionService.java", f"{TICK_BASE}/AnomalyDetectionService.java")
print("  Uploaded AnomalyDetectionService.java")

# Verify
v = ssh_cmd(f"grep -c 'MAX_MAGNITUDE' {TICK_BASE}/AnomalyDetectionService.java")
print(f"  Verification: {v.strip()} lines with MAX_MAGNITUDE")


# ============================================================
# BUILD
# ============================================================
print("\n=== Building JAR on server ===")
build_result = ssh_cmd("cd /opt/stokr/stokr-platform/stokr-lite/backend && mvn clean package -DskipTests -q 2>&1 | tail -5")
print(f"  Build: {build_result.strip()}")

# Check if JAR exists
jar_check = ssh_cmd("ls -la /opt/stokr/stokr-platform/stokr-lite/backend/target/*.jar 2>/dev/null | tail -2")
print(f"  JAR: {jar_check.strip()}")
