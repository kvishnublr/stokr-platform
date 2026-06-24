package com.stokr.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.*;

/**
 * Calls Chartink's screener API to get a list of NSE stocks meeting a scan condition.
 *
 * Setup: set chartink.session.cookie in application.properties with a valid logged-in session cookie.
 * Without it, the API returns an empty result (Chartink requires login).
 *
 * Default scan: strong uptrend + high relative volume — ideal ORB candidates.
 */
@Slf4j
@Service
public class ChartinkScannerService {

    @Value("${chartink.session.cookie:}")
    private String sessionCookie;

    private final RestTemplate restTemplate = new RestTemplate();

    public static final String DEFAULT_ORB_SCAN =
        "( latest rsi( 14 ) > 55 ) " +
        "and ( latest volume >= ( 2.0 * latest 10 day average volume ) ) " +
        "and ( latest close > latest ema( latest close , 9 ) ) " +
        "and ( latest close > latest ema( latest close , 20 ) ) " +
        "and ( latest close > latest ema( latest close , 50 ) )";

    public List<String> fetchScannerSymbols(String scanClause) {
        if (scanClause == null || scanClause.isBlank()) scanClause = DEFAULT_ORB_SCAN;
        try {
            HttpHeaders h1 = new HttpHeaders();
            h1.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36");
            h1.set("Accept", "text/html,application/xhtml+xml");
            if (!sessionCookie.isBlank()) h1.set("Cookie", sessionCookie);

            ResponseEntity<String> homeResp = restTemplate.exchange(
                "https://chartink.com/screener/", HttpMethod.GET,
                new HttpEntity<>(h1), String.class);

            String csrf = extractCsrf(homeResp.getBody());
            String cookies = buildCookieString(homeResp.getHeaders());
            if (!sessionCookie.isBlank()) {
                cookies = sessionCookie + (cookies != null && !cookies.isBlank() ? "; " + cookies : "");
            }

            if (csrf == null) {
                log.warn("Chartink: could not extract CSRF token — is session cookie configured?");
                return Collections.emptyList();
            }

            HttpHeaders h2 = new HttpHeaders();
            h2.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            h2.set("X-Requested-With", "XMLHttpRequest");
            h2.set("X-CSRF-TOKEN", csrf);
            h2.set("Referer", "https://chartink.com/screener/");
            h2.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36");
            if (cookies != null) h2.set("Cookie", cookies);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("scan_clause", scanClause);

            ResponseEntity<Map> scanResp = restTemplate.exchange(
                "https://chartink.com/screener/process", HttpMethod.POST,
                new HttpEntity<>(body, h2), Map.class);

            Map<?, ?> data = scanResp.getBody();
            if (data == null || !Boolean.TRUE.equals(data.get("success"))) {
                log.warn("Chartink scan returned no data or success=false — check session cookie");
                return Collections.emptyList();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stocks = (List<Map<String, Object>>) data.get("data");
            if (stocks == null) return Collections.emptyList();

            List<String> symbols = new ArrayList<>();
            for (Map<String, Object> s : stocks) {
                Object ns = s.get("nsecode");
                if (ns != null && !ns.toString().isBlank()) {
                    symbols.add(ns.toString().trim().toUpperCase());
                }
            }
            log.info("Chartink scan returned {} symbols: {}", symbols.size(),
                symbols.size() > 10 ? symbols.subList(0, 10) + "..." : symbols);
            return symbols;

        } catch (Exception e) {
            log.warn("Chartink scan failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public boolean isConfigured() {
        return !sessionCookie.isBlank();
    }

    private String extractCsrf(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile("<meta name=[\"']csrf-token[\"'] content=[\"']([^\"']+)[\"']").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String buildCookieString(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null || setCookies.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String c : setCookies) {
            String kv = c.split(";")[0];
            if (sb.length() > 0) sb.append("; ");
            sb.append(kv);
        }
        return sb.toString();
    }
}
