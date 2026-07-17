import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=60)
    return r.stdout + r.stderr

# 1. The key mismatch: DB says "POSITIONAL" but code checks for "DAILY"
print("=== CRITICAL BUG: timeframe mismatch ===")
print("DB strategy timeframe: POSITIONAL")
print("Code check: if (\"DAILY\".equalsIgnoreCase(strategy.getTimeframe()))")
print("RESULT: All 4 strategies go to processIntradayDeployment() instead of processDailyDeployment()")
print("")

# 2. What does processIntradayDeployment do? It expects 1-min candles
print("=== What processIntradayDeployment does with positional strategies ===")
print("It loads 1-min candles, builds ORB (first 15 candles = 9:15-9:29),")
print("computes intraday VWAP, and runs the strategy on intraday context.")
print("Positional strategies (OB, EMA50D, RSI, TRD) are designed for DAILY candles!")
print("")

# 3. What does processDailyDeployment check?
print("=== processDailyDeployment time window ===")
print("Only runs at 15:10-15:20 IST")
print("")

# 4. What's the ExecutionEngine doing for these deployments?
print("=== ExecutionEngine flow per deployment ===")
print("1. isEod = now > 15:15")
print("2. isDailyStrategy() checks timeframe == 'DAILY' → returns FALSE (strategies say POSITIONAL)")
print("3. Since !isDailyStrategy, if isEod → squareOffAll (closes all positions)")
print("4. Since !isEod (before 15:15) → processExits + processIntradayDeployment")
print("5. processIntradayDeployment feeds 1-min data to strategies designed for daily")
print("")

# 5. StrategyService - what does evaluateSignal actually do?
print("=== StrategyService ===")
print(remote("find /opt/stokr/ -name 'StrategyService.java' 2>/dev/null"))
