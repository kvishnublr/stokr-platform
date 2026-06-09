#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "nature-organic-ui"
TRADER_SCRIPT = """
    <script src="../shared/stokr-panel.js"></script>
    <script>
        StokrPanel.createTraderApp();
    </script>
"""
ADMIN_SCRIPT = """
    <script src="../shared/stokr-panel.js"></script>
    <script>
        StokrPanel.createAdminApp();
    </script>
"""

def patch(html_path: Path, new_script: str):
    text = html_path.read_text(encoding="utf-8")
    start = text.rfind("<script>")
    end = text.rfind("</script>")
    if start < 0 or end < 0:
        raise SystemExit(f"script tags not found in {html_path}")
    html_path.write_text(text[:start] + new_script.strip() + "\n" + text[end + len("</script>"):], encoding="utf-8")
    print("patched", html_path)

patch(ROOT / "trader" / "index.html", TRADER_SCRIPT)
patch(ROOT / "admin" / "index.html", ADMIN_SCRIPT)
