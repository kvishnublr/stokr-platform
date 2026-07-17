#!/usr/bin/env python3
"""Add Option Arbitrage to sidebar links"""
f = "/opt/stokr/stokr-platform/stokr-lite/frontend/src/components/Layout.jsx"
with open(f) as fp:
    code = fp.read()

if "option-arbitrage" not in code:
    # Add to trader links after Settings
    code = code.replace(
        "  { to: '/settings', label: 'Settings', icon: '⚙️' },\n];",
        "  { to: '/settings', label: 'Settings', icon: '⚙️' },\n  { to: '/admin/option-arbitrage', label: 'Option Arb', icon: '🔀' },\n];"
    )
    with open(f, 'w') as fp:
        fp.write(code)
    print("Option Arbitrage link added to sidebar")
else:
    print("Already exists")
