import sys

file_path = '/root/stokr-platform/stokr-lite/frontend/src/pages/Positions.jsx'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    "import { LivePositionsSection, BrokerPositionsPanel, CashPositionsSection, STRATEGY_LABELS } from './OptionArbitrage';", 
    "import { LivePositionsSection, BrokerPositionsPanel, CashPositionsSection, STRATEGY_LABELS, GlobalConfirmModal } from './OptionArbitrage';"
)

content = content.replace(
    '<div className="space-y-6 pb-20">',
    '<div className="space-y-6 pb-20">\n      <GlobalConfirmModal />'
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Positions.jsx patched with GlobalConfirmModal")
