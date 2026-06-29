package com.stokr.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class NseDeliveryController {

    private final NseDeliveryService deliveryService;
    private final NseDeliveryDataRepository repository;

    /** Fetch and store NSE delivery data for a specific date (or today if omitted). */
    @PostMapping("/delivery/fetch")
    public ResponseEntity<Map<String, Object>> fetchDeliveryData(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate targetDate = (date != null) ? date : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Map<String, Object> result = deliveryService.fetchAndStore(targetDate);
        return ResponseEntity.ok(result);
    }

    /**
     * Delivery leaders: stocks with high institutional accumulation.
     * Default: latest date, ≥65% delivery, EQ series only.
     * These are your next-day ORB candidates.
     */
    @GetMapping("/delivery-leaders")
    public ResponseEntity<Map<String, Object>> deliveryLeaders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "65") double minDelivPct) {

        LocalDate targetDate = date;
        if (targetDate == null) {
            targetDate = repository.findLatestDate().orElse(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        }

        List<NseDeliveryData> leaders = repository.findDeliveryLeaders(
            targetDate, BigDecimal.valueOf(minDelivPct));

        List<Map<String, Object>> rows = leaders.stream().map(d -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol",    d.getSymbol());
            row.put("close",     d.getClosePrice());
            row.put("prevClose", d.getPrevClose());
            row.put("delivPct",  d.getDelivPct());
            row.put("delivQty",  d.getDelivQty());
            row.put("totalQty",  d.getTotalQty());
            row.put("high52w",   d.getHigh52w());
            // flag if close is within 3% of 52-week high — institutional breakout candidate
            if (d.getClosePrice() != null && d.getHigh52w() != null && d.getHigh52w().compareTo(BigDecimal.ZERO) > 0) {
                double pctFrom52wHigh = (d.getHigh52w().doubleValue() - d.getClosePrice().doubleValue()) / d.getHigh52w().doubleValue();
                row.put("near52wHigh", pctFrom52wHigh <= 0.03);
                row.put("pctFrom52wHigh", Math.round(pctFrom52wHigh * 10000) / 100.0);
            } else {
                row.put("near52wHigh", false);
                row.put("pctFrom52wHigh", null);
            }
            return row;
        }).collect(Collectors.toList());

        // Split: near 52w high (institutional breakout) vs rest
        List<Map<String, Object>> breakoutCandidates = rows.stream()
            .filter(r -> Boolean.TRUE.equals(r.get("near52wHigh")))
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date",               targetDate.toString());
        result.put("totalLeaders",        rows.size());
        result.put("breakoutCandidates",  breakoutCandidates.size());
        result.put("leaders",             rows);
        result.put("breakoutLeaders",     breakoutCandidates);
        return ResponseEntity.ok(result);
    }

    /** Delivery history for a single symbol — useful for tracking accumulation trend. */
    @GetMapping("/delivery/{symbol}")
    public ResponseEntity<List<Map<String, Object>>> symbolDelivery(@PathVariable String symbol) {
        List<NseDeliveryData> history = repository.findBySymbolOrderByDateDesc(symbol);
        List<Map<String, Object>> rows = history.stream().map(d -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date",      d.getTradeDate());
            row.put("close",     d.getClosePrice());
            row.put("delivPct",  d.getDelivPct());
            row.put("delivQty",  d.getDelivQty());
            return row;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(rows);
    }
}
