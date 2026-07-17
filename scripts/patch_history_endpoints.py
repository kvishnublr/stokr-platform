#!/usr/bin/env python3
"""Add history and summary endpoints to OptionArbitrageController"""
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"
with open(f) as fp:
    code = fp.read()

# Add before the closing brace of the class
history_endpoints = '''
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, Object> response = new LinkedHashMap<>();
        try {
            var opportunities = historyService.getHistory(page, size);
            response.put("status", "ok");
            response.put("page", page);
            response.put("size", size);
            response.put("totalPages", opportunities.getTotalPages());
            response.put("totalElements", opportunities.getTotalElements());

            List<Map<String, Object>> oppMaps = new ArrayList<>();
            for (var opp : opportunities.getContent()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", opp.getId());
                m.put("scanTime", opp.getScanTime() != null ? opp.getScanTime().toString() : null);
                m.put("underlying", opp.getUnderlying());
                m.put("type", opp.getType());
                m.put("strike", opp.getStrike());
                m.put("action", opp.getAction());
                m.put("legs", opp.getLegs());
                m.put("description", opp.getDescription());
                m.put("spotPrice", opp.getSpotPrice());
                m.put("futuresPrice", opp.getFuturesPrice());
                m.put("ceEntryPrice", opp.getCeEntryPrice());
                m.put("peEntryPrice", opp.getPeEntryPrice());
                m.put("edgePoints", opp.getEdgePoints());
                m.put("edgeAfterCosts", opp.getEdgeAfterCosts());
                m.put("confidence", opp.getConfidence());
                m.put("daysToExpiry", opp.getDaysToExpiry());
                m.put("expiryDate", opp.getExpiryDate() != null ? opp.getExpiryDate().toString() : null);
                m.put("status", opp.getStatus());
                m.put("ceExitPrice", opp.getCeExitPrice());
                m.put("peExitPrice", opp.getPeExitPrice());
                m.put("pnlPoints", opp.getPnlPoints());
                m.put("pnlAmount", opp.getPnlAmount());
                m.put("pnlAfterCosts", opp.getPnlAfterCosts());
                m.put("exitTime", opp.getExitTime() != null ? opp.getExitTime().toString() : null);
                m.put("notes", opp.getNotes());
                oppMaps.add(m);
            }
            response.put("opportunities", oppMaps);
        } catch (Exception e) {
            log.error("Error fetching history: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history/summary")
    public ResponseEntity<Map<String, Object>> getDailySummary(
            @RequestParam(required = false) String date) {

        Map<String, Object> response = new LinkedHashMap<>();
        try {
            LocalDate targetDate = date != null ? LocalDate.parse(date) : LocalDate.now();
            Map<String, Object> summary = historyService.getDailySummary(targetDate);
            response.put("status", "ok");
            response.putAll(summary);
        } catch (Exception e) {
            log.error("Error fetching summary: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history/dates")
    public ResponseEntity<Map<String, Object>> getAvailableDates(
            @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            List<java.time.LocalDate> dates = historyService.getAvailableDates(days);
            response.put("status", "ok");
            response.put("dates", dates);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
'''

code = code.replace("}", history_endpoints + "\n}", 1)

with open(f, 'w') as fp:
    fp.write(code)
print("History endpoints added")
