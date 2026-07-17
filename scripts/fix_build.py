#!/usr/bin/env python3
"""Fix SignalProcessor.java compilation errors + add OptionArbitrage route to App.jsx"""
import subprocess, sys

def run(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        print(f"FAIL: {cmd}\n{r.stderr}")
    return r.stdout

# Fix SignalProcessor.java on server
# Need to fix: typicalPrice(), volume() returns long not BigDecimal
fix_script = r'''
import re

f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/SignalProcessor.java"
with open(f) as fp:
    code = fp.read()

# Fix 1: Replace c.typicalPrice() with (h+l+c)/3
code = code.replace(
    "c.typicalPrice().multiply(c.volume())",
    "(c.high().add(c.low()).add(c.close())).divide(BigDecimal.valueOf(3)).multiply(BigDecimal.valueOf(c.volume()))"
)

# Fix 2: Replace .map(Candle::volume).reduce(BigDecimal.ZERO, BigDecimal::add) for VWAP
# The VWAP reduce needs to sum long volumes then convert
old_vwap_divide = """.divide(
                            candles.stream().map(Candle::volume).reduce(BigDecimal.ZERO, BigDecimal::add),
                            4, RoundingMode.HALF_UP)"""

new_vwap_divide = """.divide(
                            BigDecimal.valueOf(candles.stream().mapToLong(Candle::volume).sum()),
                            4, RoundingMode.HALF_UP)"""

code = code.replace(old_vwap_divide, new_vwap_divide)

# Fix 3: Replace volume SMA reduce too
old_vol_reduce = """.map(Candle::volume).reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP)"""
new_vol_reduce = """.mapToLong(Candle::volume).sum())
                            volSma10 = BigDecimal.valueOf(volSum).divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP)"""

if old_vol_reduce in code:
    # More complex fix: replace the whole volSma10 block
    old_block = """    BigDecimal volSma10 = BigDecimal.ZERO;
                    if (dailyCandles.size() >= 10) {
                        volSma10 = dailyCandles.subList(dailyCandles.size() - 10, dailyCandles.size()).stream()
                            .map(Candle::volume).reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP);
                    }"""
    new_block = """    BigDecimal volSma10 = BigDecimal.ZERO;
                    if (dailyCandles.size() >= 10) {
                        long volSum = dailyCandles.subList(dailyCandles.size() - 10, dailyCandles.size()).stream()
                            .mapToLong(Candle::volume).sum();
                        volSma10 = BigDecimal.valueOf(volSum).divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP);
                    }"""
    code = code.replace(old_block, new_block)

with open(f, 'w') as fp:
    fp.write(code)

print("SignalProcessor.java fixed")
'''

run(f'ssh root@173.249.55.84 "python3 -c \'{fix_script}\'"')

# Add OptionArbitrage route to App.jsx on server
app_jsx_fix = r'''
f = "/opt/stokr/stokr-platform/stokr-lite/frontend/src/App.jsx"
with open(f) as fp:
    code = fp.read()

if "OptionArbitrage" not in code:
    # Add lazy import
    code = code.replace(
        "const AdminAuditLog = lazy(() => import('./pages/admin/AdminAuditLog'));",
        "const AdminAuditLog = lazy(() => import('./pages/admin/AdminAuditLog'));\nconst OptionArbitrage = lazy(() => import('./pages/OptionArbitrage'));"
    )
    # Add route before closing admin routes
    code = code.replace(
        '<Route path="/admin/audit-log" element={<AdminAuditLog />} />',
        '<Route path="/admin/audit-log" element={<AdminAuditLog />} />\n              <Route path="/admin/option-arbitrage" element={<OptionArbitrage />} />'
    )
    with open(f, 'w') as fp:
        fp.write(code)
    print("OptionArbitrage route added to App.jsx")
else:
    print("OptionArbitrage route already exists")
'''

run(f'ssh root@173.249.55.84 "python3 -c \'{app_jsx_fix}\'"')
print("Done")
