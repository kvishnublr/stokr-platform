#!/usr/bin/env python3
"""Add debug logging to ZerodhaSpotPriceFetcher to see raw API response"""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/ZerodhaSpotPriceFetcher.java"
with open(path, 'r') as f:
    content = f.read()

# Add debug log after the response is received
old = '''            ResponseEntity<String> response = restTemplate.exchange(
                urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode root = mapper.readTree(response.getBody());'''

new = '''            ResponseEntity<String> response = restTemplate.exchange(
                urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            log.info("Quote API response for {}: status={}, body={}", instrumentKey, response.getStatusCode(), response.getBody() != null ? response.getBody().substring(0, Math.min(200, response.getBody().length())) : "null");

            JsonNode root = mapper.readTree(response.getBody());'''

if old in content:
    content = content.replace(old, new)
    with open(path, 'w') as f:
        f.write(content)
    print("Added debug logging")
else:
    print("Could not find pattern")
