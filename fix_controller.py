import re

path = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java'
with open(path, 'r') as f:
    content = f.read()

# 1. Add import for OptionArbExecutionService
if 'OptionArbExecutionService' not in content:
    content = content.replace(
        'import com.stokr.arbitrage.OptionArbAutoExecuteService;',
        'import com.stokr.arbitrage.OptionArbAutoExecuteService;\nimport com.stokr.arbitrage.OptionArbExecutionService;'
    )

# 2. Add field for executionService
if 'executionService' not in content:
    content = content.replace(
        '    private final OptionArbAutoExecuteService autoExecService;',
        '    private final OptionArbAutoExecuteService autoExecService;\n    private final OptionArbExecutionService executionService;'
    )

# 3. Add to constructor - find the constructor and add executionService parameter
old_constructor_param = 'OptionArbAutoExecuteService autoExecService,\n                                      ExecutedTradeRepository tradeRepo)'
new_constructor_param = 'OptionArbAutoExecuteService autoExecService,\n                                      OptionArbExecutionService executionService,\n                                      ExecutedTradeRepository tradeRepo)'
content = content.replace(old_constructor_param, new_constructor_param)

old_constructor_assign = '        this.autoExecService = autoExecService;\n        this.tradeRepo = tradeRepo;'
new_constructor_assign = '        this.autoExecService = autoExecService;\n        this.executionService = executionService;\n        this.tradeRepo = tradeRepo;'
content = content.replace(old_constructor_assign, new_constructor_assign)

# 4. Replace the executeOpportunity method
old_execute = """    @PostMapping(value={"/auto-execute/execute"})
    public ResponseEntity<Map<String, Object>> executeOpportunity(@RequestParam String underlying, @RequestParam int strike, @RequestParam String action, @RequestParam double cePrice, @RequestParam double pePrice, @RequestParam double futPrice, @RequestParam double spotPrice) {
        LinkedHashMap<String, Object> resp = new LinkedHashMap<String, Object>();
        int lotSize = OptionChainService.getLotSize((String)underlying);
        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.underlying = underlying;
        opp.strike = strike;
        opp.action = action;
        opp.type = "PARITY_BREAK";
        opp.cePrice = cePrice;
        opp.pePrice = pePrice;
        opp.futuresPrice = futPrice;
        opp.spotPrice = spotPrice;
        opp.edgeAfterCosts = 0.0;
        ExecutedTrade trade = this.autoExecService.executeNew(opp);
        if (trade != null) {
            resp.put("status", "ok");
            resp.put("tradeId", trade.getId());
            resp.put("tradeStatus", trade.getStatus());
        } else {
            resp.put("status", "error");
            resp.put("error", "Execution failed");
        }
        return ResponseEntity.ok(resp);
    }
}"""

new_execute = """    @PostMapping(value={"/auto-execute/execute"})
    public ResponseEntity<Map<String, Object>> executeOpportunity(@RequestParam String underlying, @RequestParam int strike, @RequestParam String action, @RequestParam double cePrice, @RequestParam double pePrice, @RequestParam double futPrice, @RequestParam double spotPrice) {
        LinkedHashMap<String, Object> resp = new LinkedHashMap<String, Object>();
        int lotSize = OptionChainService.getLotSize((String)underlying);

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

if old_execute in content:
    content = content.replace(old_execute, new_execute)
    print("Replaced execute method")
else:
    print("ERROR: Could not find execute method to replace")
    # Try to find the execute method area
    idx = content.find('executeOpportunity')
    if idx >= 0:
        print(f"Found executeOpportunity at char {idx}")
        print(repr(content[idx-50:idx+100]))
    else:
        print("executeOpportunity not found at all")

with open(path, 'w') as f:
    f.write(content)

print("Done writing file")
