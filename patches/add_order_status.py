import sys

filepath = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java'
with open(filepath, 'r') as f:
    content = f.read()

new_endpoint = """
    @GetMapping(value={"/order-status"})
    public ResponseEntity<Map<String, Object>> getOrderStatus() {
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
                "https://api.kite.trade/orders",
                org.springframework.http.HttpMethod.GET,
                entity,
                String.class
            );
            String body = response.getBody();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");

            ArrayList<Map<String, Object>> nfoOrders = new ArrayList<>();
            if (data.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode order : data) {
                    String exchange = order.path("exchange").asText("");
                    String status = order.path("status").asText("");
                    if ("NFO".equals(exchange) && ("COMPLETE".equals(status) || "OPEN".equals(status) || "TRIGGER PENDING".equals(status))) {
                        LinkedHashMap<String, Object> o = new LinkedHashMap<>();
                        o.put("orderId", order.path("order_id").asText());
                        o.put("symbol", order.path("tradingsymbol").asText());
                        o.put("side", order.path("transaction_type").asText());
                        o.put("orderType", order.path("order_type").asText());
                        o.put("quantity", order.path("quantity").asInt());
                        o.put("price", order.path("price").asDouble());
                        o.put("status", status);
                        o.put("filledQty", order.path("filled_quantity").asInt());
                        o.put("pendingQty", order.path("pending_quantity").asInt());
                        o.put("avgPrice", order.path("average_price").asDouble());
                        o.put("placedAt", order.path("order_timestamp").asText());
                        o.put("variety", order.path("variety").asText());
                        o.put("product", order.path("product").asText());
                        nfoOrders.add(o);
                    }
                }
            }
            resp.put("status", "ok");
            resp.put("orders", nfoOrders);
        } catch (Exception e) {
            log.error("Error fetching order status: {}", e.getMessage(), e);
            resp.put("status", "error");
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping(value={"/cancel-order"})
    public ResponseEntity<Map<String, Object>> cancelOrder(@RequestParam String orderId) {
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
            rt.exchange(
                "https://api.kite.trade/orders/" + orderId + "/cancel",
                org.springframework.http.HttpMethod.DELETE,
                entity,
                String.class
            );
            resp.put("status", "ok");
            resp.put("message", "Cancel request sent for " + orderId);
        } catch (Exception e) {
            log.error("Cancel order failed: {}", e.getMessage(), e);
            resp.put("status", "error");
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

"""

marker = "    private Map<String, Object> buildBasketLeg"
if marker in content:
    content = content.replace(marker, new_endpoint + marker)
    with open(filepath, 'w') as f:
        f.write(content)
    print('Endpoint added successfully')
else:
    print('ERROR: marker not found')
    sys.exit(1)
