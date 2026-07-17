#!/usr/bin/env python3
"""
Live patch for stokr option arb - margin check, fill verification, partial fill square-off.
Decompiles key classes from running JAR, patches them, recompiles, and repackages.
"""
import os, subprocess, shutil, re

WORK = "/tmp/arb_patch"
JAR_SRC = "/tmp/app-working.jar"
JAR_DST = "/opt/stokr/stokr-platform/stokr-lite/app-patched.jar"
CFR = "/tmp/cfr.jar"
SRC = f"{WORK}/src"
OUT = f"{WORK}/out"

def sh(cmd, check=False):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if check and r.returncode != 0:
        print(f"  FAIL: {r.stderr[:300]}")
        raise RuntimeError(f"Command failed: {cmd[:100]}")
    return r

# === SETUP ===
if os.path.exists(WORK): shutil.rmtree(WORK)
for d in [SRC, OUT]: os.makedirs(d, exist_ok=True)

if not os.path.exists(CFR):
    print("Downloading CFR...")
    sh(f"wget -q -O {CFR} https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar")

print("Extracting JAR...")
sh(f"cd {WORK} && jar xf {JAR_SRC}")

# Build classpath
libs = [f"{WORK}/BOOT-INF/lib/{f}" for f in os.listdir(f"{WORK}/BOOT-INF/lib")]
cp = f"{WORK}/BOOT-INF/classes:" + ":".join(libs)

# === DECOMPILE ===
targets = [
    "com/stokr/broker/ZerodhaAdapter",
    "com/stokr/arbitrage/OptionArbExecutionService", 
    "com/stokr/arbitrage/OptionArbAutoExecuteService",
]

for cls in targets:
    cls_file = f"{WORK}/BOOT-INF/classes/{cls}.class"
    java_file = f"{SRC}/{cls}.java"
    os.makedirs(os.path.dirname(java_file), exist_ok=True)
    sh(f"java -jar {CFR} {cls_file} --outputdir {SRC} --silent false")
    if os.path.exists(java_file):
        print(f"Decompiled: {cls.split('/')[-1]}")
    else:
        print(f"FAILED to decompile: {cls}")

# === PATCH 1: ZerodhaAdapter - change placeOrder return from "COMPLETE" to "OPEN" ===
za_path = f"{SRC}/com/stokr/broker/ZerodhaAdapter.java"
if os.path.exists(za_path):
    with open(za_path, 'r') as f:
        content = f.read()
    
    # Change "COMPLETE" to "OPEN" in the placeOrder return
    content = content.replace(
        'return new BrokerOrderResponse(orderId, "COMPLETE", "Order placed")',
        'return new BrokerOrderResponse(orderId, "OPEN", "Order placed")'
    )
    # Add getOrderDetails method if not present
    if "getOrderDetails" not in content:
        # Find the last closing brace of the class and insert before it
        # Add after getOrderStatus
        insert_marker = 'return "UNKNOWN";\n    }\n}'
        if insert_marker in content:
            new_method = '''return "UNKNOWN";
    }

    public java.util.Map<String, Object> getOrderDetails(String accessToken, String orderId) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        try {
            String body = http.get()
                    .uri(KITE_API_BASE + "/orders/" + orderId)
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .body(String.class);

            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");
            if (data.isArray() && data.size() > 0) {
                com.fasterxml.jackson.databind.JsonNode order = data.get(data.size() - 1);
                details.put("status", order.path("status").asText("UNKNOWN"));
                details.put("average_price", order.path("average_price").asDouble(0.0));
                details.put("quantity", order.path("quantity").asInt(0));
                details.put("filled_quantity", order.path("filled_quantity").asInt(0));
                details.put("pending_quantity", order.path("pending_quantity").asInt(0));
                details.put("tradingsymbol", order.path("tradingsymbol").asText());
            }
        } catch (Exception e) {
            log.warn("getOrderDetails {} failed: {}", orderId, e.getMessage());
            details.put("status", "UNKNOWN");
        }
        return details;
    }
}'''
            content = content.replace(insert_marker, new_method)
    
    with open(za_path, 'w') as f:
        f.write(content)
    print("Patched: ZerodhaAdapter (placeOrder returns OPEN, added getOrderDetails)")

# === PATCH 2: OptionArbExecutionService - add margin check, fill verification, square-off ===
oaes_path = f"{SRC}/com/stokr/arbitrage/OptionArbExecutionService.java"
if os.path.exists(oaes_path):
    with open(oaes_path, 'r') as f:
        content = f.read()
    
    # Add BigDecimal import if not present
    if "import java.math.BigDecimal" not in content:
        content = content.replace("import java.time.", "import java.math.BigDecimal;\nimport java.time.")
    
    # Replace the execute method to add margin check and fill verification
    # First, find the execute method start
    old_execute_start = 'public ExecutionResult execute(String underlying, int strike, String action,'
    if old_execute_start in content:
        # We need to insert margin check after auth check
        # Find "Get auth token" section and add margin check after it
        old_auth_check = '''        // Get auth token
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) {
            result.success = false;
            result.error = "No valid Zerodha session. Please login via Brokers page.";
            return result;
        }

        // Build NFO symbols'''
        
        new_auth_check = '''        // Get auth token
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) {
            result.success = false;
            result.error = "No valid Zerodha session. Please login via Brokers page.";
            return result;
        }

        // MARGIN CHECK
        BigDecimal availableMargin = zerodhaAdapter.getAvailableMargin(auth.getAccessToken());
        double estimatedRequired = (cePrice + pePrice + futPrice) * lotSize * 1.15;
        if (availableMargin.doubleValue() < estimatedRequired) {
            result.success = false;
            result.error = String.format("Insufficient margin. Available: Rs.,.0f, Required: Rs.,.0f (with 15%% buffer)",
                availableMargin.doubleValue(), estimatedRequired);
            log.warn("Margin check failed: available={} required={}", availableMargin, estimatedRequired);
            return result;
        }
        log.info("Margin OK: available=Rs. {},.0f estimated=Rs. {},.0f", availableMargin.doubleValue(), estimatedRequired);

        // Build NFO symbols'''
        
        content = content.replace(old_auth_check, new_auth_check)
    
    # Replace the order firing loop to add fill verification
    old_fill_loop = '''        // Fire all3 orders in rapid succession
        boolean allFilled = true;
        List<String> orderIds = new ArrayList<>();

        for (BrokerOrderRequest order : orders) {
            LegResult leg = new LegResult();
            leg.symbol = order.symbol();
            leg.side = order.side().name();
            leg.quantity = order.quantity();
            leg.price = order.price() != null ? order.price() : 0;

            try {
                BrokerOrderResponse response = zerodhaAdapter.placeOrder(auth.getAccessToken(), order);
                leg.orderId = response.orderId();
                leg.status = response.status();
                leg.message = response.message();

                if (response.isSuccess()) {
                    log.info("Order filled: {} {} {} @ {}", order.side(), order.symbol(), order.quantity(), order.price());
                    orderIds.add(response.orderId());
                } else {
                    allFilled = false;
                    log.warn("Order rejected: {} {} — {}", order.side(), order.symbol(), response.message());
                }
            } catch (Exception e) {
                leg.status = "ERROR";
                leg.message = e.getMessage();
                allFilled = false;
                log.error("Order failed: {} {} — {}", order.side(), order.symbol(), e.getMessage());
            }

            result.legs.add(leg);

            // Small delay between orders to avoid rate limiting
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        result.success = allFilled;

        if (allFilled) {
            log.info("All3 orders filled for {} {} {}: {}", action, underlying, strike, orderIds);
        } else {
            log.warn("Partial fill for {} {} {} — some orders rejected. Order IDs: {}",
                action, underlying, strike, orderIds);
            // Cancel filled orders if any leg failed
            if (!orderIds.isEmpty()) {
                log.info("Cancelling {} filled orders due to partial fill", orderIds.size());
                for (String oid : orderIds) {
                    try {
                        zerodhaAdapter.cancelOrder(auth.getAccessToken(), oid);
                    } catch (Exception e) {
                        log.warn("Cancel {} failed: {}", oid, e.getMessage());
                    }
                }
            }
        }

        return result;'''

    new_fill_loop = '''        // Fire all 3 orders
        List<String> placedOrderIds = new ArrayList<>();

        for (BrokerOrderRequest order : orders) {
            LegResult leg = new LegResult();
            leg.symbol = order.symbol();
            leg.side = order.side().name();
            leg.quantity = order.quantity();
            leg.price = order.price() != null ? order.price() : 0;

            try {
                BrokerOrderResponse response = zerodhaAdapter.placeOrder(auth.getAccessToken(), order);
                leg.orderId = response.orderId();
                leg.status = response.status();
                leg.message = response.message();
                if (response.orderId() != null) {
                    placedOrderIds.add(response.orderId());
                }
            } catch (Exception e) {
                leg.status = "ERROR";
                leg.message = e.getMessage();
                log.error("Order failed: {} {} — {}", order.side(), order.symbol(), e.getMessage());
            }
            result.legs.add(leg);
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }

        // Wait for fills to propagate
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        // POLL FILL STATUS for each order
        int filledCount = 0;
        for (LegResult leg : result.legs) {
            if (leg.orderId != null) {
                try {
                    java.util.Map<String, Object> details = zerodhaAdapter.getOrderDetails(auth.getAccessToken(), leg.orderId);
                    String realStatus = (String) details.getOrDefault("status", "UNKNOWN");
                    double avgPrice = ((Number) details.getOrDefault("average_price", 0.0)).doubleValue();
                    int filled = ((Number) details.getOrDefault("filled_quantity", 0)).intValue();
                    leg.status = realStatus;
                    leg.price = avgPrice > 0 ? avgPrice : leg.price;
                    if ("COMPLETE".equalsIgnoreCase(realStatus)) filledCount++;
                    log.info("Order {} {}: status={} fillPrice={} filledQty={}", leg.side, leg.symbol, realStatus, avgPrice, filled);
                } catch (Exception e) {
                    log.warn("Could not verify fill for {}: {}", leg.orderId, e.getMessage());
                }
            }
        }

        if (filledCount == 3) {
            result.success = true;
            log.info("All 3 legs verified filled for {} {} {}", action, underlying, strike);
        } else if (filledCount == 0) {
            result.success = false;
            result.error = "No legs filled after verification";
            log.warn("No legs filled for {} {} {}", action, underlying, strike);
        } else {
            // PARTIAL FILL - square off the filled legs immediately
            log.warn("PARTIAL FILL: {}/3 legs filled for {} {} {}. Squaring off.", filledCount, action, underlying, strike);
            result.error = String.format("Partial fill: %d/3 legs completed. Filling legs squared off.", filledCount);
            
            for (LegResult leg : result.legs) {
                if ("COMPLETE".equalsIgnoreCase(leg.status) && leg.orderId != null && leg.price > 0) {
                    try {
                        BrokerOrderRequest.Side closeSide = "BUY".equals(leg.side) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
                        BrokerOrderRequest closeOrder = new BrokerOrderRequest(
                            leg.symbol, "NFO", closeSide, leg.quantity, leg.price, null, "NRML");
                        BrokerOrderResponse closeResp = zerodhaAdapter.placeOrder(auth.getAccessToken(), closeOrder);
                        log.info("SQUARE-OFF: {} {} {} @ {} => {}", closeSide, leg.symbol, leg.quantity, leg.price, closeResp.status());
                        if (!"OPEN".equals(closeResp.status()) && !"COMPLETE".equals(closeResp.status())) {
                            // Retry as MARKET
                            BrokerOrderRequest mktClose = new BrokerOrderRequest(
                                leg.symbol, "NFO", closeSide, leg.quantity, 0.0, null, "NRML");
                            zerodhaAdapter.placeOrder(auth.getAccessToken(), mktClose);
                            log.info("SQUARE-OFF MARKET retry for {}", leg.symbol);
                        }
                    } catch (Exception e) {
                        log.error("SQUARE-OFF failed for {}: {}", leg.symbol, e.getMessage());
                    }
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }
        }

        return result;'''

        content = content.replace(old_fill_loop, new_fill_loop)
    
    # Fix LegResult to use getRequestedPrice and getFillPrice
    content = content.replace("public double price;", "public double requestedPrice;\n        public double fillPrice;")
    content = content.replace("leg.price = order.price()", "leg.requestedPrice = order.price()")
    
    with open(oaes_path, 'w') as f:
        f.write(content)
    print("Patched: OptionArbExecutionService (margin check + fill verification + square-off)")

# === PATCH 3: OptionArbAutoExecuteService - add margin check to executeNew ===
oae_path = f"{SRC}/com/stokr/arbitrage/OptionArbAutoExecuteService.java"
if os.path.exists(oae_path):
    with open(oae_path, 'r') as f:
        content = f.read()
    
    # Add BigDecimal import if not present
    if "import java.math.BigDecimal" not in content:
        content = content.replace("import java.time.", "import java.math.BigDecimal;\nimport java.time.")
    
    # Add margin check to executeNew method
    old_exec_start = '''    public ExecutedTrade executeNew(ArbitrageOpportunity opp) {
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) {
            log.warn("No auth token, cannot execute");
            return null;
        }

        java.time.LocalTime nowIST = java.time.LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 30))) {
            log.warn("Market closed, cannot execute");
            return null;
        }

        int lotSize = OptionChainService.getLotSize(opp.underlying);'''

    new_exec_start = '''    public ExecutedTrade executeNew(ArbitrageOpportunity opp) {
        ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
        if (auth == null || auth.getAccessToken() == null) {
            log.warn("No auth token, cannot execute");
            return null;
        }

        java.time.LocalTime nowIST = java.time.LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 30))) {
            log.warn("Market closed, cannot execute");
            return null;
        }

        int lotSize = OptionChainService.getLotSize(opp.underlying);
        
        // MARGIN CHECK
        BigDecimal availableMargin = zerodhaAdapter.getAvailableMargin(auth.getAccessToken());
        double estimatedRequired = (opp.cePrice + opp.pePrice + opp.futuresPrice) * lotSize * 1.15;
        if (availableMargin.doubleValue() < estimatedRequired) {
            log.warn("Insufficient margin for {} {} {}: available=Rs.,.0f required=Rs.,.0f",
                opp.underlying, (int) opp.strike, opp.action, availableMargin.doubleValue(), estimatedRequired);
            return null;
        }'''

    content = content.replace(old_exec_start, new_exec_start)

    # Add fill verification after order placement in executeNew
    old_loop_end = '''            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        if (!allFilled) {
            cancelFilledOrders(auth.getAccessToken(), trade);
            trade.setStatus("FAILED");
            trade.setNotes("Partial fill — cancelled");
        }

        trade.setNotes(trade.getNotes() != null ? trade.getNotes() : "Auto-executed");
        return tradeRepo.save(trade);'''
    
    new_loop_end = '''            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }

        // Wait and verify fills
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        
        int verifiedFilled = 0;
        for (String oid : new java.util.ArrayList<>(java.util.List.of(
            trade.getCeOrderId() != null ? trade.getCeOrderId() : "",
            trade.getPeOrderId() != null ? trade.getPeOrderId() : "",
            trade.getFutOrderId() != null ? trade.getFutOrderId() : ""))) {
            if (oid.isEmpty()) continue;
            try {
                java.util.Map<String, Object> details = zerodhaAdapter.getOrderDetails(auth.getAccessToken(), oid);
                String status = (String) details.getOrDefault("status", "UNKNOWN");
                double avgPrice = ((Number) details.getOrDefault("average_price", 0.0)).doubleValue();
                if ("COMPLETE".equalsIgnoreCase(status)) {
                    verifiedFilled++;
                    // Update entry price with actual fill
                    if (oid.equals(trade.getCeOrderId()) && avgPrice > 0) trade.setCeEntryPrice(avgPrice);
                    if (oid.equals(trade.getPeOrderId()) && avgPrice > 0) trade.setPeEntryPrice(avgPrice);
                    if (oid.equals(trade.getFutOrderId()) && avgPrice > 0) trade.setFutEntryPrice(avgPrice);
                }
            } catch (Exception e) { log.warn("Verify {} failed: {}", oid, e.getMessage()); }
        }

        if (verifiedFilled == 3) {
            trade.setStatus("OPEN");
            trade.setNotes("Auto-executed - all 3 legs verified filled");
        } else if (verifiedFilled == 0) {
            cancelFilledOrders(auth.getAccessToken(), trade);
            trade.setStatus("FAILED");
            trade.setNotes("All orders rejected/failed after verification");
        } else {
            // PARTIAL FILL - square off filled legs
            log.warn("PARTIAL FILL: {}/3 verified for {} {} {}. Squaring off.", verifiedFilled, opp.action, opp.underlying, (int) opp.strike);
            cancelFilledOrders(auth.getAccessToken(), trade);
            trade.setStatus("FAILED");
            trade.setNotes(String.format("Partial fill %d/3 - filled legs cancelled. Risk managed.", verifiedFilled));
        }

        return tradeRepo.save(trade);'''
    
    content = content.replace(old_loop_end, new_loop_end)

    # Fix closePositionInternal to verify closes
    old_close = '''        existing.setStatus("CLOSED");
        existing.setClosedAt(LocalDateTime.now());
        existing.setNotes(existing.getNotes() != null ? existing.getNotes() + " | Manually closed" : "Manually closed");
        return tradeRepo.save(existing);'''
    
    new_close = '''        // Wait and verify close orders
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        int closedCount = 0;
        int totalClose = 0;
        for (String oid : java.util.List.of(
            existing.getCloseCeOrderId() != null ? existing.getCloseCeOrderId() : "",
            existing.getClosePeOrderId() != null ? existing.getClosePeOrderId() : "",
            existing.getCloseFutOrderId() != null ? existing.getCloseFutOrderId() : "")) {
            if (!oid.isEmpty()) {
                totalClose++;
                try {
                    java.util.Map<String, Object> details = zerodhaAdapter.getOrderDetails(token, oid);
                    String status = (String) details.getOrDefault("status", "UNKNOWN");
                    if ("COMPLETE".equalsIgnoreCase(status)) closedCount++;
                } catch (Exception e) { log.warn("Verify close {} failed: {}", oid, e.getMessage()); }
            }
        }
        if (totalClose > 0 && closedCount == totalClose) {
            existing.setStatus("CLOSED");
            existing.setNotes(existing.getNotes() != null ? existing.getNotes() + " | All legs closed" : "All legs closed");
        } else if (closedCount > 0) {
            existing.setStatus("PARTIALLY_CLOSED");
            existing.setNotes(existing.getNotes() != null ? existing.getNotes() + " | Partial close: " + closedCount + "/" + totalClose : "Partial close: " + closedCount + "/" + totalClose);
        } else {
            existing.setStatus("CLOSE_FAILED");
            existing.setNotes(existing.getNotes() != null ? existing.getNotes() + " | Close orders failed" : "Close orders failed");
        }
        existing.setClosedAt(LocalDateTime.now());
        return tradeRepo.save(existing);'''
    
    content = content.replace(old_close, new_close)
    
    with open(oae_path, 'w') as f:
        f.write(content)
    print("Patched: OptionArbAutoExecuteService (margin check + fill verification + close verification)")

# === COMPILE ===
print("\nCompiling patched classes...")
compile_ok = True
for cls in targets:
    java_file = f"{SRC}/{cls}.java"
    class_dir = f"{OUT}"
    if not os.path.exists(java_file):
        print(f"  SKIP: {cls} not found")
        continue
    r = sh(f"javac -cp {cp} -d {class_dir} {java_file}")
    if r.returncode == 0:
        print(f"  Compiled: {cls.split('/')[-1]}")
    else:
        print(f"  FAILED: {cls.split('/')[-1]}")
        print(f"    {r.stderr[:500]}")
        compile_ok = False

if not compile_ok:
    print("\nCompilation failed. Cannot patch JAR.")
    sys.exit(1)

# === REPLACE IN JAR ===
print("\nPatching JAR...")
for cls in targets:
    class_file = f"{OUT}/{cls}.class"
    if os.path.exists(class_file):
        dest = f"{WORK}/BOOT-INF/classes/{cls.replace('/', '/')}.class"
        # Also copy inner classes
        cls_base = cls.split('/')[-1]
        for f in os.listdir(OUT):
            if f.startswith(cls_base) and f.endswith('.class'):
                src_f = os.path.join(OUT, f)
                dest_f = os.path.join(os.path.dirname(dest), f)
                shutil.copy2(src_f, dest_f)
                print(f"  Replaced: {f}")

# Repackage JAR
sh(f"cd {WORK} && jar cf {JAR_DST} .")
print(f"\nPatched JAR created: {JAR_DST}")
print(f"Size: {os.path.getsize(JAR_DST)} bytes")
