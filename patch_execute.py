#!/usr/bin/env python3
"""Patch: Add execute endpoint to OptionArbitrageController"""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"
with open(path, 'r') as f:
    content = f.read()

# 1. Add execution service to constructor
old_fields = """    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final OptionArbHistoryService historyService;
    private final CalendarSpreadService calendarSpreadService;
    private final VolSurfaceService volSurfaceService;"""

new_fields = """    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final OptionArbHistoryService historyService;
    private final CalendarSpreadService calendarSpreadService;
    private final VolSurfaceService volSurfaceService;
    private final OptionArbExecutionService executionService;"""

content = content.replace(old_fields, new_fields)

# 2. Add /execute endpoint — find the last closing brace of the class
# Find the getHistorySummary method and add after it
execute_endpoint = '''
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestParam String underlying,
            @RequestParam int strike,
            @RequestParam String action,
            @RequestParam double cePrice,
            @RequestParam double pePrice,
            @RequestParam double futPrice,
            @RequestParam double spotPrice) {

        Map<String, Object> response = new LinkedHashMap<>();

        int lotSize = OptionChainService.getLotSize(underlying);

        try {
            OptionArbExecutionService.ExecutionResult result = executionService.execute(
                underlying, strike, action, cePrice, pePrice, futPrice, spotPrice, lotSize);

            response.put("status", result.isSuccess() ? "ok" : "error");
            response.put("action", result.getAction());
            response.put("underlying", result.getUnderlying());
            response.put("strike", result.getStrike());
            response.put("lotSize", lotSize);

            List<Map<String, Object>> legMaps = new ArrayList<>();
            for (OptionArbExecutionService.LegResult leg : result.getLegs()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("symbol", leg.getSymbol());
                lm.put("side", leg.getSide());
                lm.put("quantity", leg.getQuantity());
                lm.put("price", leg.getPrice());
                lm.put("orderId", leg.getOrderId());
                lm.put("status", leg.getStatus());
                lm.put("message", leg.getMessage());
                legMaps.add(lm);
            }
            response.put("legs", legMaps);

            if (!result.isSuccess() && result.getError() != null) {
                response.put("error", result.getError());
            }

        } catch (Exception e) {
            log.error("Execution failed: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }
'''

# Find the last closing brace of the class and insert before it
# The class ends with just "}" on the last line
last_brace = content.rfind('}')
if last_brace > 0:
    content = content[:last_brace] + execute_endpoint + '\n' + content[last_brace:]

with open(path, 'w') as f:
    f.write(content)

print("Added /execute endpoint to OptionArbitrageController")
