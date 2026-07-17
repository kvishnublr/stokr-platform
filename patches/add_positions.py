import sys

filepath = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java'
with open(filepath, 'r') as f:
    content = f.read()

new_endpoint = """
    @GetMapping(value={"/positions"})
    public ResponseEntity<Map<String, Object>> getPositions() {
        LinkedHashMap<String, Object> resp = new LinkedHashMap<>();
        try {
            String token = this.spotFetcher.getAuthToken();
            if (token == null) {
                resp.put("status", "error");
                resp.put("error", "No auth token");
                return ResponseEntity.ok(resp);
            }
            String apiKey = "zazlrld244cc6jf0";
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-Kite-Version", "3");
            headers.set("Authorization", "token " + apiKey + ":" + token);
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
            org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
            org.springframework.http.ResponseEntity<String> response = rt.exchange(
                "https://api.kite.trade/portfolio/positions",
                org.springframework.http.HttpMethod.GET,
                entity,
                String.class
            );
            String body = response.getBody();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");

            ArrayList<Map<String, Object>> nfoPositions = new ArrayList<>();
            double totalPnl = 0;
            double totalMtm = 0;
            if (data.isObject()) {
                com.fasterxml.jackson.databind.JsonNode dayPositions = data.path("day");
                if (dayPositions.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode pos : dayPositions) {
                        String exchange = pos.path("exchange").asText("");
                        double quantity = pos.path("quantity").asDouble(0);
                        if ("NFO".equals(exchange) && quantity != 0) {
                            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
                            p.put("tradingsymbol", pos.path("tradingsymbol").asText());
                            p.put("exchange", exchange);
                            p.put("instrumentType", pos.path("instrument_type").asText());
                            p.put("quantity", (int) quantity);
                            p.put("avgPrice", pos.path("average_price").asDouble());
                            p.put("ltp", pos.path("ltp").asDouble());
                            p.put("pnl", pos.path("pnl").asDouble());
                            p.put("mtm", pos.path("mtm").asDouble());
                            p.put("buyQuantity", pos.path("buy_quantity").asInt());
                            p.put("sellQuantity", pos.path("sell_quantity").asInt());
                            p.put("buyPrice", pos.path("buy_price").asDouble());
                            p.put("sellPrice", pos.path("sell_price").asDouble());
                            p.put("product", pos.path("product").asText());
                            nfoPositions.add(p);
                            totalPnl += pos.path("pnl").asDouble();
                            totalMtm += pos.path("mtm").asDouble();
                        }
                    }
                }
            }
            resp.put("status", "ok");
            resp.put("positions", nfoPositions);
            resp.put("totalPnl", Math.round(totalPnl * 100.0) / 100.0);
            resp.put("totalMtm", Math.round(totalMtm * 100.0) / 100.0);
            resp.put("count", nfoPositions.size());
        } catch (Exception e) {
            log.error("Error fetching positions: {}", e.getMessage(), e);
            resp.put("status", "error");
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping(value={"/exit-position"})
    public ResponseEntity<Map<String, Object>> exitPosition(
            @RequestParam String symbol,
            @RequestParam String exchange,
            @RequestParam String product,
            @RequestParam int quantity,
            @RequestParam String transactionType) {
        LinkedHashMap<String, Object> resp = new LinkedHashMap<>();
        try {
            String token = this.spotFetcher.getAuthToken();
            if (token == null) {
                resp.put("status", "error");
                resp.put("error", "No auth token");
                return ResponseEntity.ok(resp);
            }
            String apiKey = "zazlrld244cc6jf0";
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-Kite-Version", "3");
            headers.set("Content-Type", "application/x-www-form-urlencoded");
            headers.set("Authorization", "token " + apiKey + ":" + token);
            String body = "tradingsymbol=" + symbol
                + "&exchange=" + exchange
                + "&transaction_type=" + transactionType
                + "&order_type=MARKET"
                + "&quantity=" + quantity
                + "&product=" + product
                + "&validity=DAY";
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(body, headers);
            org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
            org.springframework.http.ResponseEntity<String> response = rt.exchange(
                "https://api.kite.trade/orders/regular",
                org.springframework.http.HttpMethod.POST,
                entity,
                String.class
            );
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
            resp.put("status", "ok");
            resp.put("orderId", root.path("data").path("order_id").asText());
            resp.put("message", transactionType + " " + quantity + " " + symbol + " placed");
        } catch (Exception e) {
            log.error("Exit position failed: {}", e.getMessage(), e);
            resp.put("status", "error");
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

"""

marker = "    @GetMapping(value={\"/order-status\"})"
if marker in content:
    content = content.replace(marker, new_endpoint + marker)
    with open(filepath, 'w') as f:
        f.write(content)
    print('Positions endpoint added successfully')
else:
    print('ERROR: marker not found')
    sys.exit(1)
