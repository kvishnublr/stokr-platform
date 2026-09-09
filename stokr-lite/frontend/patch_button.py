with open('/root/stokr-platform/stokr-lite/frontend/src/pages/OptionArbitrage.jsx', 'r', encoding='utf-8') as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if 'EXIT SET' in line and 'bg-red-500' in lines[i-1]:
        # Inject the ADJUST button right before the EXIT SET button
        lines.insert(i-1, '''                          <button onClick={(e) => { e.stopPropagation(); setAdjusterGroup(g); }} 
                            className=" bg-indigo-500 hover:bg-indigo-600 text-white px-3 py-1 rounded-lg font-bold text-[10px] shadow-sm transition-all mr-2\>
 🛠️ ADJUST
 </button>\\n''')
 break

with open('/root/stokr-platform/stokr-lite/frontend/src/pages/OptionArbitrage.jsx', 'w', encoding='utf-8') as f:
 f.writelines(lines)
