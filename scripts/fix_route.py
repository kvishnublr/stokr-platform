#!/usr/bin/env python3
"""Move option-arbitrage route outside AdminRoute"""
f = "/opt/stokr/stokr-platform/stokr-lite/frontend/src/App.jsx"
with open(f) as fp:
    code = fp.read()

# Remove from AdminRoute
code = code.replace(
    '              <Route path="/admin/audit-log" element={<AdminAuditLog />} />\n              <Route path="/admin/option-arbitrage" element={<OptionArbitrage />} />',
    '              <Route path="/admin/audit-log" element={<AdminAuditLog />} />'
)

# Add after Settings, before AdminRoute
code = code.replace(
    '<Route element={<AdminRoute />}>',
    '            <Route path="/option-arbitrage" element={<OptionArbitrage />} />\n            <Route element={<AdminRoute />}>'
)

with open(f, 'w') as fp:
    fp.write(code)
print("Route moved outside AdminRoute")
