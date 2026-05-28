package com.stokr.broker.kite;

import com.stokr.broker.adapter.OutboundIpRestClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves canonical symbols (e.g. MCX:CRUDEOIL) to active Kite tradingsymbols (near-month future).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZerodhaKiteInstrumentResolver {

    private static final String KITE_BASE = "https://api.kite.trade";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long CACHE_TTL_SECONDS = 3600;

    private final OutboundIpRestClientFactory ipClientFactory;

    private final ConcurrentHashMap<String, CachedInstruments> cache = new ConcurrentHashMap<>();

    public record ResolvedInstrument(
            String exchange,
            String tradingsymbol,
            String canonicalBase,
            LocalDate expiry,
            String product) {
    }

    public ResolvedInstrument resolve(
            String symbol,
            String exchangeHint,
            String apiKey,
            String accessToken,
            String outboundIp) {
        if (apiKey == null || apiKey.isBlank() || accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Kite credentials required to resolve instrument");
        }
        String[] parsed = parseSymbolExchange(symbol, exchangeHint);
        String exchange = parsed[0];
        String base = parsed[1];
        if (base.isBlank()) {
            throw new IllegalArgumentException("Symbol is blank");
        }

        if ("MCX".equals(exchange) || "NFO".equals(exchange)) {
            return resolveNearMonthFuture(exchange, base, apiKey, accessToken, outboundIp);
        }

        String product = "MIS";
        if ("NSE".equals(exchange) || "BSE".equals(exchange)) {
            product = "MIS";
        }
        return new ResolvedInstrument(exchange, base, base, null, product);
    }

    private ResolvedInstrument resolveNearMonthFuture(
            String exchange,
            String base,
            String apiKey,
            String accessToken,
            String outboundIp) {
        List<InstrumentRow> rows = loadInstruments(exchange, apiKey, accessToken, outboundIp);
        LocalDate today = LocalDate.now(IST);
        boolean mini = base.endsWith("M") && base.length() > 4;

        InstrumentRow match = rows.stream()
                .filter(r -> matchesFutureRoot(r, base, mini))
                .filter(r -> r.expiry != null && r.expiry.isAfter(today))
                .min(Comparator.comparing((InstrumentRow r) -> r.expiry, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(r -> r.tradingsymbol.length()))
                .orElseThrow(() -> new IllegalStateException(
                        "No active " + exchange + " future contract found for " + base
                                + " — check Kite instrument master / contract expiry"));

        String product = "MCX".equals(exchange) ? "NRML" : "MIS";
        log.info("kite.instrument.resolved exchange={} base={} tradingsymbol={} expiry={} product={}",
                exchange, base, match.tradingsymbol, match.expiry, product);
        return new ResolvedInstrument(exchange, match.tradingsymbol, base, match.expiry, product);
    }

    static boolean matchesFutureRoot(InstrumentRow row, String base, boolean wantMini) {
        if (row.tradingsymbol == null || row.tradingsymbol.isBlank()) {
            return false;
        }
        if (!isFutureInstrumentType(row.instrumentType)) {
            return false;
        }
        String sym = row.tradingsymbol.toUpperCase(Locale.ROOT);
        String b = base.toUpperCase(Locale.ROOT);
        if (wantMini) {
            return sym.startsWith(b);
        }
        if (!sym.startsWith(b) || sym.length() <= b.length()) {
            return false;
        }
        // Exclude mini contracts when base is the main symbol (CRUDEOIL vs CRUDEOILM...)
        if (sym.length() > b.length() && sym.charAt(b.length()) == 'M') {
            char afterM = b.length() + 1 < sym.length() ? sym.charAt(b.length() + 1) : 0;
            if (Character.isDigit(afterM) || Character.isLetter(afterM)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFutureInstrumentType(String instrumentType) {
        if (instrumentType == null) {
            return false;
        }
        String t = instrumentType.toUpperCase(Locale.ROOT);
        return t.contains("FUT");
    }

    private List<InstrumentRow> loadInstruments(
            String exchange,
            String apiKey,
            String accessToken,
            String outboundIp) {
        String cacheKey = exchange + ":" + apiKey;
        CachedInstruments cached = cache.get(cacheKey);
        if (cached != null && cached.loadedAt().plusSeconds(CACHE_TTL_SECONDS).isAfter(Instant.now())) {
            return cached.rows();
        }
        RestClient http = ipClientFactory.clientFor(outboundIp);
        String csv = http.get()
                .uri(KITE_BASE + "/instruments/" + exchange)
                .header(HttpHeaders.AUTHORIZATION, "token " + apiKey + ":" + accessToken)
                .retrieve()
                .body(String.class);
        List<InstrumentRow> rows = parseInstrumentCsv(csv);
        cache.put(cacheKey, new CachedInstruments(rows, Instant.now()));
        return rows;
    }

    static List<InstrumentRow> parseInstrumentCsv(String csv) {
        List<InstrumentRow> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        String[] lines = csv.split("\\R");
        if (lines.length < 2) {
            return out;
        }
        String[] hdr = splitCsvLine(lines[0]);
        Map<String, Integer> idx = new java.util.LinkedHashMap<>();
        for (int i = 0; i < hdr.length; i++) {
            idx.put(hdr[i].trim().toLowerCase(Locale.ROOT), i);
        }
        Integer symIdx = idx.get("tradingsymbol");
        Integer expIdx = idx.get("expiry");
        Integer typeIdx = idx.get("instrument_type");
        if (symIdx == null) {
            return out;
        }
        for (int i = 1; i < lines.length; i++) {
            String[] p = splitCsvLine(lines[i]);
            if (p.length <= symIdx) {
                continue;
            }
            InstrumentRow row = new InstrumentRow();
            row.tradingsymbol = p[symIdx].trim();
            if (expIdx != null && p.length > expIdx) {
                row.expiry = parseExpiry(p[expIdx].trim());
            }
            if (typeIdx != null && p.length > typeIdx) {
                row.instrumentType = p[typeIdx].trim();
            }
            if (!row.tradingsymbol.isBlank()) {
                out.add(row);
            }
        }
        return out;
    }

    private static LocalDate parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            if (raw.length() >= 10) {
                return LocalDate.parse(raw.substring(0, 10));
            }
            return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(String[]::new);
    }

    /** Parses "NSE:ITC" or "ITC" into [exchange, tradingsymbol/base]. */
    public static String[] parseSymbolExchange(String symbol, String exchangeHint) {
        if (symbol != null && symbol.contains(":")) {
            String[] parts = symbol.split(":", 2);
            return new String[]{parts[0].trim().toUpperCase(Locale.ROOT), parts[1].trim().toUpperCase(Locale.ROOT)};
        }
        String exchange = (exchangeHint != null && !exchangeHint.isBlank())
                ? exchangeHint.trim().toUpperCase(Locale.ROOT)
                : "NSE";
        return new String[]{exchange, symbol != null ? symbol.trim().toUpperCase(Locale.ROOT) : ""};
    }

    static final class InstrumentRow {
        String tradingsymbol;
        LocalDate expiry;
        String instrumentType;
    }

    private record CachedInstruments(List<InstrumentRow> rows, Instant loadedAt) {
    }
}
