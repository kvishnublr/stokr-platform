package com.stokr.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NseDeliveryService {

    private final NseDeliveryDataRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter NSE_DATE_FMT = DateTimeFormatter.ofPattern("ddMMuuuu");

    // Runs at 6:15 PM IST Mon–Fri — NSE publishes delivery data ~6 PM
    @Scheduled(cron = "0 15 18 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledFetch() {
        LocalDate today = LocalDate.now(IST);
        log.info("Scheduled NSE delivery fetch for {}", today);
        fetchAndStore(today);
    }

    public Map<String, Object> fetchAndStore(LocalDate date) {
        if (repository.existsByTradeDate(date)) {
            log.info("NSE delivery data for {} already in DB, skipping", date);
            return Map.of("status", "SKIPPED", "date", date.toString(), "reason", "already loaded");
        }

        String url = buildUrl(date);
        log.info("Fetching NSE bhavcopy from: {}", url);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.set("Accept-Language", "en-US,en;q=0.5");
            headers.set("Referer", "https://www.nseindia.com/");
            headers.set("Connection", "keep-alive");

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("NSE bhavcopy fetch failed: status={}", response.getStatusCode());
                return Map.of("status", "ERROR", "date", date.toString(), "reason", "HTTP " + response.getStatusCode());
            }

            List<NseDeliveryData> records = parseCsv(response.getBody(), date);
            int saved = 0;
            int skipped = 0;

            for (NseDeliveryData rec : records) {
                try {
                    repository.save(rec);
                    saved++;
                } catch (DataIntegrityViolationException e) {
                    skipped++;
                }
            }

            log.info("NSE delivery data for {}: parsed={} saved={} skipped={}", date, records.size(), saved, skipped);
            return Map.of("status", "OK", "date", date.toString(), "parsed", records.size(), "saved", saved, "skipped", skipped);

        } catch (Exception e) {
            log.error("NSE delivery fetch failed for {}: {}", date, e.getMessage());
            return Map.of("status", "ERROR", "date", date.toString(), "reason", e.getMessage());
        }
    }

    private List<NseDeliveryData> parseCsv(String csv, LocalDate date) {
        List<NseDeliveryData> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return result;

            // Detect column positions dynamically
            String[] headers = headerLine.split(",");
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                colIndex.put(headers[i].trim().toUpperCase(), i);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", -1);

                try {
                    String symbol = get(parts, colIndex, "SYMBOL");
                    String series = get(parts, colIndex, "SERIES");

                    // Only EQ series (skip ETF, MF, etc.)
                    if (!"EQ".equals(series)) continue;
                    if (symbol == null || symbol.isBlank()) continue;

                    NseDeliveryData rec = NseDeliveryData.builder()
                        .tradeDate(date)
                        .symbol(symbol)
                        .series(series)
                        .openPrice(dec(parts, colIndex, "OPEN_PRICE"))
                        .highPrice(dec(parts, colIndex, "HIGH_PRICE"))
                        .lowPrice(dec(parts, colIndex, "LOW_PRICE"))
                        .closePrice(dec(parts, colIndex, "CLOSE_PRICE"))
                        .prevClose(dec(parts, colIndex, "PREV_CL_PR"))
                        .totalQty(lng(parts, colIndex, "NET_TRAD_QTY"))
                        .delivQty(lng(parts, colIndex, "DELIV_QTY"))
                        .delivPct(dec(parts, colIndex, "DELIV_PER"))
                        .high52w(dec(parts, colIndex, "52W_HIGH"))
                        .low52w(dec(parts, colIndex, "52W_LOW"))
                        .build();

                    if (rec.getDelivPct() != null) {
                        result.add(rec);
                    }
                } catch (Exception e) {
                    log.debug("Skip line: {} — {}", line, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("CSV parse error: {}", e.getMessage());
        }
        return result;
    }

    private String buildUrl(LocalDate date) {
        String datePart = date.format(NSE_DATE_FMT);
        return "https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_" + datePart + ".csv";
    }

    private String get(String[] parts, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        return (i != null && i < parts.length) ? parts[i].trim() : null;
    }

    private BigDecimal dec(String[] parts, Map<String, Integer> idx, String col) {
        String v = get(parts, idx, col);
        if (v == null || v.isBlank() || "-".equals(v)) return null;
        try { return new BigDecimal(v); } catch (Exception e) { return null; }
    }

    private Long lng(String[] parts, Map<String, Integer> idx, String col) {
        String v = get(parts, idx, col);
        if (v == null || v.isBlank() || "-".equals(v)) return null;
        try { return Long.parseLong(v.replace(",", "")); } catch (Exception e) {
            try { return new BigDecimal(v).longValue(); } catch (Exception e2) { return null; }
        }
    }
}
