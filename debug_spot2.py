#!/usr/bin/env python3
"""Add debug logging to getSpotPrice method only"""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/ZerodhaSpotPriceFetcher.java"
with open(path, 'r') as f:
    content = f.read()

# Only patch getSpotPrice method - find the exact pattern
old = '''            ResponseEntity<String> response = restTemplate.exchange(
                urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode root = mapper.readTree(response.getBody());
            JsonNode data = root.path("data");

            if (data.isObject() && data.size() > 0) {'''

new = '''            ResponseEntity<String> response = restTemplate.exchange(
                urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            String body = response.getBody();
            log.info("Quote API {} response: status={} bodyLen={} body={}",
                instrumentKey, response.getStatusCode(),
                body != null ? body.length() : 0,
                body != null && body.length() < 500 ? body : (body != null ? body.substring(0, 500) : "null"));

            JsonNode root = mapper.readTree(body);
            JsonNode data = root.path("data");

            if (data.isObject() && data.size() > 0) {'''

if old in content:
    content = content.replace(old, new)
    with open(path, 'w') as f:
        f.write(content)
    print("Added debug to getSpotPrice only")
else:
    print("Could not find pattern")
