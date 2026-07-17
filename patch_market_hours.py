#!/usr/bin/env python3
"""Fix: Add market hours check to /live-prices-batch and /live-prices endpoints.
Also update frontend to disable live-prices query when market is closed.
"""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"

with open(path, 'r') as f:
    content = f.read()

# Add market hours check to live-prices-batch endpoint
old_batch = """    @GetMapping("/live-prices-batch")
    public ResponseEntity<Map<String, Object>> getLivePricesBatch(
            @RequestParam(defaultValue = "ALL") String underlying) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {"""

new_batch = """    @GetMapping("/live-prices-batch")
    public ResponseEntity<Map<String, Object>> getLivePricesBatch(
            @RequestParam(defaultValue = "ALL") String underlying) {

        Map<String, Object> response = new LinkedHashMap<>();

        // Market hours guard: return empty prices after hours
        java.time.LocalTime istNow = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (istNow.getHour() < 9 || (istNow.getHour() == 9 && istNow.getMinute() < 15) ||
            istNow.getHour() > 15 || (istNow.getHour() == 15 && istNow.getMinute() > 30)) {
            response.put("status", "ok");
            response.put("prices", Map.of());
            response.put("marketClosed", true);
            return ResponseEntity.ok(response);
        }

        try {"""

if "marketClosed" in content:
    print("1. live-prices-batch market hours guard already exists")
else:
    content = content.replace(old_batch, new_batch)
    print("1. Added market hours guard to /live-prices-batch")

# Add same guard to live-prices (single)
old_live = """    @GetMapping("/live-prices")
    public ResponseEntity<Map<String, Object>> getLivePrices(
            @RequestParam String underlying,
            @RequestParam int strike,
            @RequestParam String expiry) {

        Map<String, Object> response = new LinkedHashMap<>();
        try {"""

new_live = """    @GetMapping("/live-prices")
    public ResponseEntity<Map<String, Object>> getLivePrices(
            @RequestParam String underlying,
            @RequestParam int strike,
            @RequestParam String expiry) {

        Map<String, Object> response = new LinkedHashMap<>();

        java.time.LocalTime istNow2 = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (istNow2.getHour() < 9 || (istNow2.getHour() == 9 && istNow2.getMinute() < 15) ||
            istNow2.getHour() > 15 || (istNow2.getHour() == 15 && istNow2.getMinute() > 30)) {
            response.put("status", "ok");
            response.put("marketClosed", true);
            return ResponseEntity.ok(response);
        }

        try {"""

if "marketClosed" in content and "istNow2" in content:
    print("2. live-prices market hours guard already exists")
else:
    content = content.replace(old_live, new_live)
    print("2. Added market hours guard to /live-prices")

with open(path, 'w') as f:
    f.write(content)

print("\nDone. Rebuild Docker.")
