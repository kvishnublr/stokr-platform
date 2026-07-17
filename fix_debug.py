#!/usr/bin/env python3
"""Remove bad debug log from ZerodhaSpotPriceFetcher"""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/ZerodhaSpotPriceFetcher.java"
with open(path, 'r') as f:
    content = f.read()

old = '''            log.info("Quote API response for {}: status={}, body={}", instrumentKey, response.getStatusCode(), response.getBody() != null ? response.getBody().substring(0, Math.min(200, response.getBody().length())) : "null");

            JsonNode root = mapper.readTree(response.getBody());'''

new = '''            JsonNode root = mapper.readTree(response.getBody());'''

if old in content:
    content = content.replace(old, new)
    with open(path, 'w') as f:
        f.write(content)
    print("Removed debug log")
else:
    print("Already clean")
