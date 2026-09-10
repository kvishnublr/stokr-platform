with open('/root/stokr-platform/stokr-lite/frontend/src/pages/OptionArbitrage.jsx', 'r', encoding='utf-8') as f:
    content = f.read()

import re

# 1. Add state to LivePositionsSection
content = re.sub(
    r'(const \[expandedPosId, setExpandedPosId\] = useState\(null\);)',
    r'\\1\\n  const [adjusterGroup, setAdjusterGroup] = useState(null);',
    content
)

# 2. Add PositionAdjusterModal at the end of LivePositionsSection
content = re.sub(
    r'(          </div>\\n        \}\\)\\n      </div>\\n    </div>\\n  \);\\n\})',
    r'          </div>\\n        )}\\n      </div>\\n\\n      <PositionAdjusterModal\\n        isOpen={!!adjusterGroup}\\n        onClose={() => setAdjusterGroup(null)}\\n        group={adjusterGroup}\\n        executionBroker={executionBroker}\\n        onExecute={() => refetch()}\\n      />\\n    </div>\\n  );\\n}',
    content
)

# 3. Add the ADJUST POSITION button
button_code = '''<div className= flex justify-between items-center mb-2>
                                <span className=font-bold text-slate-800 text-xs uppercase block>Open Position Payoff -- {p.underlying} {p.action}:</span>
                                <button onClick={(e) => { e.stopPropagation(); setAdjusterGroup(p); }} className=bg-indigo-600 hover:bg-indigo-700 text-white px-3 py-1 rounded shadow text-[10px] font-bold>🛠️ ADJUST POSITION</button>
                              </div>'''

content = content.replace('<span className=font-bold text-slate-800 text-xs uppercase block>Open Position Payoff -- {p.underlying} {p.action}:</span>', button_code)

with open('/root/stokr-platform/stokr-lite/frontend/src/pages/OptionArbitrage.jsx', 'w', encoding='utf-8') as f:
    f.write(content)
