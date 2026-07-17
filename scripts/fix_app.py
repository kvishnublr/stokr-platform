#!/usr/bin/env python3
"""Add OptionArbitrage route to App.jsx on server"""
f = "/opt/stokr/stokr-platform/stokr-lite/frontend/src/App.jsx"
with open(f) as fp:
    code = fp.read()

if "OptionArbitrage" not in code:
    # Add lazy import after AdminAuditLog
    code = code.replace(
        "const AdminAuditLog = lazy(() => import('./pages/admin/AdminAuditLog'));",
        "const AdminAuditLog = lazy(() => import('./pages/admin/AdminAuditLog'));\nconst OptionArbitrage = lazy(() => import('./pages/OptionArbitrage'));"
    )
    # Add route
    code = code.replace(
        '<Route path="/admin/audit-log" element={<AdminAuditLog />} />',
        '<Route path="/admin/audit-log" element={<AdminAuditLog />} />\n              <Route path="/admin/option-arbitrage" element={<OptionArbitrage />} />'
    )
    with open(f, 'w') as fp:
        fp.write(code)
    print("OptionArbitrage route added to App.jsx")
else:
    print("OptionArbitrage route already exists")
