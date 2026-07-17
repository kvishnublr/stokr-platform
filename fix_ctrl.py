#!/usr/bin/env python3
import re

path = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java'
with open(path, 'r') as f:
    content = f.read()

# 1. Add import
if 'OptionArbExecutionService' not in content.split('public class')[0]:
    content = content.replace(
        'import com.stokr.arbitrage.OptionArbAutoExecuteService;',
        'import com.stokr.arbitrage.OptionArbAutoExecuteService;\nimport com.stokr.arbitrage.OptionArbExecutionService;'
    )

# 2. Add field
if 'private final OptionArbExecutionService executionService' not in content:
    content = content.replace(
        'private final OptionArbAutoExecuteService autoExecService;',
        'private final OptionArbAutoExecuteService autoExecService;\n    private final OptionArbExecutionService executionService;'
    )

# 3. Add to constructor param
if 'OptionArbExecutionService executionService,' not in content:
    content = content.replace(
        'OptionArbAutoExecuteService autoExecService, ExecutedTradeRepository tradeRepo)',
        'OptionArbAutoExecuteService autoExecService, OptionArbExecutionService executionService, ExecutedTradeRepository tradeRepo)'
    )

# 4. Add to constructor body
if 'this.executionService = executionService;' not in content:
    content = content.replace(
        'this.autoExecService = autoExecService;',
        'this.autoExecService = autoExecService;\n        this.executionService = executionService;'
    )

# 5. Add UnderlyingConfig record
if 'private record UnderlyingConfig' not in content:
    content = content.replace(
        'private static final Map<String, UnderlyingConfig>',
        'private record UnderlyingConfig(String name, String spotKey, String futuresPrefix) {}\n    private static final Map<String, UnderlyingConfig>'
    )

# 6. Fix raw types
content = content.replace('List open = this.tradeRepo.findByStatusOrderByExecutedAtDesc("OPEN")',
                          'List<ExecutedTrade> open = this.tradeRepo.findByStatusOrderByExecutedAtDesc("OPEN")')
content = content.replace('Optional opt = this.tradeRepo.findById((Object)tradeId)',
                          'Optional<ExecutedTrade> opt = this.tradeRepo.findById(tradeId)')
content = content.replace('(ExecutedTrade)opt.get()', 'opt.get()')

# 7. Remove CFR comment header
lines = content.split('\n')
start_idx = 0
for i, line in enumerate(lines):
    if line.strip().startswith('package '):
        start_idx = i
        break
# Also remove preceding blank/comment lines
while start_idx > 0 and (lines[start_idx-1].strip() == '' or lines[start_idx-1].strip().startswith('*')):
    start_idx -= 1
content = '\n'.join(lines[start_idx:])

# 8. Replace execute method
old_start = content.find('@PostMapping(value={"/auto-execute/execute"})')
if old_start < 0:
    old_start = content.find('@PostMapping(value={"/auto-execute/execute"}')
if old_start >= 0:
    # Find method start - go back to find access modifier
    method_start = old_start
    while method_start > 0 and content[method_start-1] in ' \t\n\r':
        method_start -= 1
    # Find the line start
    nl = content.rfind('\n', 0, method_start)
    if nl >= 0:
        method_start = nl + 1

    # Find end - count braces from the @PostMapping
    brace_pos = content.find('{', old_start)
    depth = 1
    pos = brace_pos + 1
    while pos < len(content) and depth > 0:
        if content[pos] == '{':
            depth += 1
        elif content[pos] == '}':
            depth -= 1
        pos += 1
    method_end = pos

    new_method = """    @PostMapping(value={"/auto-execute/execute"})
    public ResponseEntity<Map<String, Object>> executeOpportunity(@RequestParam String underlying, @RequestParam int strike, @RequestParam String action, @RequestParam double cePrice, @RequestParam double pePrice, @RequestParam double futPrice, @RequestParam double spotPrice) {
        LinkedHashMap<String, Object> resp = new LinkedHashMap<String, Object>();
        int lotSize = OptionChainService.getLotSize(underlying);

        OptionArbExecutionService.ExecutionResult execResult = this.executionService.execute(underlying, strike, action, cePrice, pePrice, futPrice, spotPrice, lotSize);

        resp.put("success", execResult.isSuccess());
        resp.put("action", execResult.getAction());
        resp.put("underlying", execResult.getUnderlying());
        resp.put("strike", execResult.getStrike());
        resp.put("error", execResult.getError());

        ArrayList<Map<String, Object>> legs = new ArrayList<Map<String, Object>>();
        for (OptionArbExecutionService.LegResult leg : execResult.getLegs()) {
            LinkedHashMap<String, Object> lm = new LinkedHashMap<String, Object>();
            lm.put("symbol", leg.getSymbol());
            lm.put("side", leg.getSide());
            lm.put("orderId", leg.getOrderId());
            lm.put("status", leg.getStatus());
            lm.put("message", leg.getMessage());
            lm.put("price", leg.getPrice());
            lm.put("quantity", leg.getQuantity());
            legs.add(lm);
        }
        resp.put("legs", legs);

        ExecutedTrade trade = new ExecutedTrade();
        trade.setUnderlying(underlying);
        trade.setStrike(strike);
        trade.setAction(action);
        trade.setExpiryDate(optionChainService.getMonthlyExpiry());
        trade.setLotSize(lotSize);
        trade.setStatus(execResult.isSuccess() ? "OPEN" : "FAILED");

        for (OptionArbExecutionService.LegResult leg : execResult.getLegs()) {
            if (leg.getSymbol() != null && leg.getSymbol().endsWith("CE")) {
                trade.setCeSymbol(leg.getSymbol());
                trade.setCeOrderId(leg.getOrderId());
                trade.setCeEntryPrice(leg.getPrice());
            } else if (leg.getSymbol() != null && leg.getSymbol().endsWith("PE")) {
                trade.setPeSymbol(leg.getSymbol());
                trade.setPeOrderId(leg.getOrderId());
                trade.setPeEntryPrice(leg.getPrice());
            } else if (leg.getSymbol() != null && leg.getSymbol().endsWith("FUT")) {
                trade.setFutSymbol(leg.getSymbol());
                trade.setFutOrderId(leg.getOrderId());
                trade.setFutEntryPrice(leg.getPrice());
            }
        }

        trade.setNotes(execResult.isSuccess() ? "Manually executed" : execResult.getError());
        ExecutedTrade saved = tradeRepo.save(trade);
        resp.put("tradeId", saved.getId());
        resp.put("tradeStatus", saved.getStatus());

        return ResponseEntity.ok(resp);
    }
}"""
    content = content[:method_start] + new_method
    print("Replaced execute method")
else:
    print("ERROR: execute method not found")

with open(path, 'w') as f:
    f.write(content)
print("Done")
