#!/usr/bin/env python3
"""Fix RestTemplate double-encoding: use java.net.URI instead of raw URL string"""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/ZerodhaSpotPriceFetcher.java"
with open(path, 'r') as f:
    content = f.read()

# Fix getSpotPrice - use URI to prevent double-encoding
old_single = '''            String encodedKey = instrumentKey.replace(" ", "%20");
            String urlStr = "https://api.kite.trade/quote?i=" + encodedKey;

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + apiKey + ":" + token);
            headers.set("X-Kite-Version", "3");

            ResponseEntity<String> response = restTemplate.exchange(
                urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);'''

new_single = '''            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + apiKey + ":" + token);
            headers.set("X-Kite-Version", "3");

            java.net.URI uri = java.net.URI.create("https://api.kite.trade/quote");
            org.springframework.web.util.UriComponentsBuilder builder =
                org.springframework.web.util.UriComponentsBuilder.fromUri(uri)
                    .queryParam("i", instrumentKey);
            java.net.URI finalUri = builder.build().encode(java.nio.charset.StandardCharsets.UTF_8).toUri();

            ResponseEntity<String> response = restTemplate.exchange(
                finalUri, HttpMethod.GET, new HttpEntity<>(headers), String.class);'''

content = content.replace(old_single, new_single)

# Also fix getSpotAndFutures - same issue
old_batch = '''            String urlStr = "https://api.kite.trade/quote?i=" + encodedSpot + "&i=" + encodedFut;

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + apiKey + ":" + token);
            headers.set("X-Kite-Version", "3");

            ResponseEntity<String> response = restTemplate.exchange(
                urlStr, HttpMethod.GET, new HttpEntity<>(headers), String.class);'''

new_batch = '''            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + apiKey + ":" + token);
            headers.set("X-Kite-Version", "3");

            java.net.URI uri = java.net.URI.create("https://api.kite.trade/quote");
            org.springframework.web.util.UriComponentsBuilder builder =
                org.springframework.web.util.UriComponentsBuilder.fromUri(uri)
                    .queryParam("i", spotInstrument)
                    .queryParam("i", futInstrument);
            java.net.URI finalUri = builder.build().encode(java.nio.charset.StandardCharsets.UTF_8).toUri();

            ResponseEntity<String> response = restTemplate.exchange(
                finalUri, HttpMethod.GET, new HttpEntity<>(headers), String.class);'''

content = content.replace(old_batch, new_batch)

# Remove the debug log too
old_debug = '''            String body = response.getBody();
            log.info("Quote API {} response: status={} bodyLen={} body={}",
                instrumentKey, response.getStatusCode(),
                body != null ? body.length() : 0,
                body != null && body.length() < 500 ? body : (body != null ? body.substring(0, 500) : "null"));

            JsonNode root = mapper.readTree(body);'''

new_debug = '''            JsonNode root = mapper.readTree(response.getBody());'''

content = content.replace(old_debug, new_debug)

with open(path, 'w') as f:
    f.write(content)
print("Fixed URL encoding in ZerodhaSpotPriceFetcher")
