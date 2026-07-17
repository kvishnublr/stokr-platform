#!/usr/bin/env python3
"""Comprehensive fix for all option arb issues:
1. Fix ZerodhaSpotPriceFetcher to handle HttpURLConnection redirect issue
2. Fix bid/ask extraction (depth is array)
3. Fix SKEW_ANOMALY: add futures hedge leg + edge calculation
4. Lower MIN_EDGE_AFTER_COSTS to 300
"""

# ============================================================
# FIX 1: ZerodhaSpotPriceFetcher - use RestTemplate instead of
#         HttpURLConnection which may follow redirects to /quote/
# ============================================================
f1 = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/ZerodhaSpotPriceFetcher.java"
with open(f1) as fp:
    code = fp.read()

# Replace HttpURLConnection with RestTemplate (which OptionChainService uses successfully)
old_fetch = """            String urlStr = "https://api.kite.trade/quote?i=" + encodedKey;

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "token " + apiKey + ":" + token);
            conn.setRequestProperty("X-Kite-Version", "3");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();"""

new_fetch = """            String urlStr = "https://api.kite.trade/quote?i=" + encodedKey;

            java.net.URI uri = java.net.URI.create(urlStr);
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "token " + apiKey + ":" + token)
                .header("X-Kite-Version", "3")
                .timeout(java.time.Duration.ofSeconds(10))
                .GET()
                .build();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            String body = response.body();"""

code = code.replace(old_fetch, new_fetch)

# Remove unused imports and add needed ones
code = code.replace("import java.io.*;", "import java.io.ByteArrayInputStream;")
code = code.replace("import java.net.HttpURLConnection;", "")
code = code.replace("import java.net.URL;", "")

with open(f1, 'w') as fp:
    fp.write(code)
print("Fixed ZerodhaSpotPriceFetcher: HttpURLConnection -> HttpClient")

# ============================================================
# FIX 2: OptionChainService - fix bid/ask (array), skew edge, threshold
# ============================================================
f2 = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionChainService.java"
with open(f2) as fp:
    code = fp.read()

# Fix bid/ask extraction - depth.buy and depth.sell are ARRAYS, take first element
old_bid_ask = """                        quote.bid = getNestedDouble(quoteData, "depth", "buy", "price");
                        quote.ask = getNestedDouble(quoteData, "depth", "sell", "price");"""

new_bid_ask = """                        // depth.buy and depth.sell are arrays - take best bid/ask (first element)
                        quote.bid = getDepthPrice(quoteData, "buy");
                        quote.ask = getDepthPrice(quoteData, "sell");"""

code = code.replace(old_bid_ask, new_bid_ask)

# Add getDepthPrice helper method before the getNestedDouble method
depth_helper = """
    @SuppressWarnings("unchecked")
    private double getDepthPrice(Map<String, Object> quoteData, String side) {
        try {
            Map<String, Object> depth = (Map<String, Object>) quoteData.get("depth");
            if (depth == null) return 0;
            java.util.List<Map<String, Object>> levels = (java.util.List<Map<String, Object>>) depth.get(side);
            if (levels == null || levels.isEmpty()) return 0;
            Map<String, Object> best = levels.get(0);
            Object price = best.get("price");
            if (price instanceof Number) return ((Number) price).doubleValue();
        } catch (Exception e) {
            // depth parsing failed
        }
        return 0;
    }

"""

code = code.replace(
    "    @SuppressWarnings(\"unchecked\")\n    private double getNestedDouble(",
    depth_helper + "    @SuppressWarnings(\"unchecked\")\n    private double getNestedDouble("
)

# Lower threshold
code = code.replace(
    "private static final double MIN_EDGE_AFTER_COSTS = 500.0;",
    "private static final double MIN_EDGE_AFTER_COSTS = 300.0;"
)

# Fix SKEW_ANOMALY: add futures hedge and proper edge calculation
old_skew = """    private ArbitrageOpportunity buildSkewOpportunity(String underlying, int strike,
            OptionQuote ceQuote, OptionQuote peQuote, double ceIV, double peIV,
            double daysToExpiry, double spotPrice) {

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.type = "SKEW_ANOMALY";
        opp.underlying = underlying;
        opp.strike = strike;
        opp.edgePoints = (peIV - ceIV) * 100;
        opp.daysToExpiry = daysToExpiry;
        opp.spotPrice = spotPrice;
        opp.cePrice = ceQuote.lastPrice;
        opp.pePrice = peQuote.lastPrice;
        opp.confidence = 70;
        opp.action = "SELL_PUT_BUY_CALL";
        opp.legs = String.format(
            "SELL %d PE @ %.1f (IV %.1f%%) | BUY %d CE @ %.1f (IV %.1f%%)",
            strike, peQuote.lastPrice, peIV * 100, strike, ceQuote.lastPrice, ceIV * 100);
        opp.description = String.format(
            "Skew anomaly: Put IV %.1f%% >> Call IV %.1f%%. Sell expensive puts, buy cheap calls",
            peIV * 100, ceIV * 100);

        return opp;
    }"""

new_skew = """    private ArbitrageOpportunity buildSkewOpportunity(String underlying, int strike,
            OptionQuote ceQuote, OptionQuote peQuote, double ceIV, double peIV,
            double daysToExpiry, double spotPrice, double futuresPrice) {

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.type = "SKEW_ANOMALY";
        opp.underlying = underlying;
        opp.strike = strike;
        opp.daysToExpiry = daysToExpiry;
        opp.spotPrice = spotPrice;
        opp.futuresPrice = futuresPrice;
        opp.cePrice = ceQuote.lastPrice;
        opp.pePrice = peQuote.lastPrice;
        opp.ceBid = ceQuote.bid;
        opp.ceAsk = ceQuote.ask;
        opp.peBid = peQuote.bid;
        opp.peAsk = peQuote.ask;

        // Skew trade: Sell PE (high IV) + Buy CE (low IV) + Sell FUT (delta hedge)
        // This is a calendar skew play, hedged with futures
        double ivDiff = (peIV - ceIV) * 100;
        opp.edgePoints = ivDiff;
        opp.confidence = Math.min(80, 60 + (int)(ivDiff / 2));

        // Edge estimation: reversion of 20% of IV spread
        double expectedIVReversion = ivDiff * 0.20;
        double vegaPer1Pct = spotPrice * 0.0001 * Math.sqrt(daysToExpiry / 365.0);
        double grossEdge = expectedIVReversion * vegaPer1Pct * 50; // NIFTY lot=50

        // Costs: 3-leg trade (Sell PE + Buy CE + Sell FUT)
        int lotSize = "BANKNIFTY".equals(underlying) ? 15 : 50;
        double avgPremium = (ceQuote.lastPrice + peQuote.lastPrice) / 2;
        double futPrice = futuresPrice > 0 ? futuresPrice : spotPrice;

        double sttEntry = peQuote.lastPrice * 0.001 * lotSize + futPrice * 0.0002 * lotSize;
        double sttExit = ceQuote.lastPrice * 0.001 * lotSize + futPrice * 0.0002 * lotSize;
        double totalSTT = sttEntry + sttExit;
        double brokerage = 20 * 6;
        double turnover = (ceQuote.lastPrice + peQuote.lastPrice + futPrice) * lotSize * 2;
        double exchange = turnover * 0.0000345;
        double sebi = turnover * 0.000001;
        double gst = (brokerage + exchange) * 0.18;
        double totalCosts = totalSTT + brokerage + exchange + sebi + gst;

        opp.edgeAfterCosts = Math.max(0, grossEdge - totalCosts);

        opp.action = "SELL_PUT_BUY_CALL";
        opp.legs = String.format(
            "SELL %d PE @ %.1f (IV %.1f%%) | BUY %d CE @ %.1f (IV %.1f%%) | SELL %s FUT @ %.0f",
            strike, peQuote.lastPrice, peIV * 100, strike, ceQuote.lastPrice, ceIV * 100,
            underlying, futuresPrice > 0 ? futuresPrice : spotPrice);
        opp.description = String.format(
            "Skew anomaly: Put IV %.1f%% >> Call IV %.1f%%. IV diff %.1f%%, expected 20%% reversion. Edge Rs.%.0f after costs",
            peIV * 100, ceIV * 100, ivDiff, opp.edgeAfterCosts);

        return opp;
    }"""

code = code.replace(old_skew, new_skew)

# Fix the skew call site to pass futuresPrice
code = code.replace(
    """                        opportunities.add(buildSkewOpportunity(
                            underlying, strike, ceQuote, peQuote, ceIV, peIV, daysToExpiry, spotPrice));""",
    """                        opportunities.add(buildSkewOpportunity(
                            underlying, strike, ceQuote, peQuote, ceIV, peIV, daysToExpiry, spotPrice, futuresPrice));"""
)

with open(f2, 'w') as fp:
    fp.write(code)
print("Fixed OptionChainService: bid/ask, skew edge, threshold")
