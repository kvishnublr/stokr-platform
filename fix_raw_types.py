import re

path = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java'
with open(path, 'r') as f:
    content = f.read()

# Fix raw types from CFR decompilation
# Replace "List open" with "List<ExecutedTrade> open"
content = content.replace('List open = this.tradeRepo.findByStatusOrderByExecutedAtDesc("OPEN")', 
                          'List<ExecutedTrade> open = this.tradeRepo.findByStatusOrderByExecutedAtDesc("OPEN")')

# Replace raw Optional with typed Optional
content = content.replace('Optional opt = this.tradeRepo.findById((Object)tradeId)',
                          'Optional<ExecutedTrade> opt = this.tradeRepo.findById(tradeId)')

# Replace cast-heavy opt.get() usage
content = content.replace('(ExecutedTrade)opt.get()', 'opt.get()')

# Fix raw List in scan method - arbitrage opps
content = content.replace('List allOpps = new ArrayList', 'List<ArbitrageOpportunity> allOpps = new ArrayList<ArbitrageOpportunity>')
content = content.replace('List opps = optionChainService.scanParityBreaks', 'List<ArbitrageOpportunity> opps = optionChainService.scanParityBreaks')

# Fix "for (Object" to proper types where needed
content = content.replace('for (Object t : open)', 'for (ExecutedTrade t : open)')

# Fix raw Map usage
content = content.replace('Map<String, Object> m = new HashMap()', 'Map<String, Object> m = new LinkedHashMap<String, Object>()')

# Fix raw Optional in closeTrade
content = content.replace('Optional opt = this.tradeRepo.findById((Object)tradeId)', 
                          'Optional<ExecutedTrade> opt = this.tradeRepo.findById(tradeId)')

# Remove the CFR comment header (first 38 lines)
lines = content.split('\n')
if 'Decompiled with CFR' in lines[0] if lines else False:
    # Find where the actual package declaration starts
    pkg_idx = next(i for i, l in enumerate(lines) if l.strip().startswith('package '))
    # Keep everything from package onwards but remove the import block comments
    content = '\n'.join(lines[pkg_idx:])

# Remove "Could not load" comment block
content = re.sub(r'/\*\s*\n\s*\*\s*Could not load the following classes:.*?\*/', '', content, flags=re.DOTALL)

# Remove individual " *  com.stokr..." lines  
content = re.sub(r'\s*\*\s*com\.stokr\.\S+', '', content)
content = re.sub(r'\s*\*\s*org\.slf4j\.\S+', '', content)
content = re.sub(r'\s*\*\s*org\.springframework\.\S+', '', content)
content = re.sub(r'\s*\*\s*java\.lang\.\S+', '', content)

# Fix "CallSite" import (CFR artifact)
content = content.replace('import java.lang.invoke.CallSite;\n', '')

# Clean up consecutive blank lines
content = re.sub(r'\n{4,}', '\n\n\n', content)

# Fix raw ArrayList constructor
content = content.replace('new ArrayList<Map<String, Object>>()', 'new ArrayList<>()')
content = content.replace('new LinkedHashMap<String, Object>()', 'new LinkedHashMap<>()')
content = content.replace('new HashMap<String, Object>()', 'new LinkedHashMap<>()')

# Fix map.get returns that need casting
content = content.replace('Object ltp = qMap.get("last_price")', 'Object ltp = qMap.get("last_price")')

with open(path, 'w') as f:
    f.write(content)

print("Done fixing raw types")
