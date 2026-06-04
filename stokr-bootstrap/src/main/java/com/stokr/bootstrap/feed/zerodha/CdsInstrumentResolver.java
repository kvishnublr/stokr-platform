package com.stokr.bootstrap.feed.zerodha;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Resolves Zerodha CDS instrument tokens for canonical currency pair symbols. */
public final class CdsInstrumentResolver {

    private static final List<String> MAJOR_PAIRS = List.of("USDINR", "EURINR");

    private CdsInstrumentResolver() {
    }

    public static Map<String, Integer> resolveMajorPairs(String instrumentsCsv) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (instrumentsCsv == null || instrumentsCsv.isBlank()) {
            return out;
        }

        BufferedReader br = new BufferedReader(new StringReader(instrumentsCsv));
        String header = br.readLine();
        if (header == null) {
            return out;
        }
        String[] hc = parseCsvLine(header);
        int itCol = col(hc, "instrument_token");
        int tsCol = col(hc, "tradingsymbol");
        int typeCol = col(hc, "instrument_type");
        int expiryCol = col(hc, "expiry");
        if (itCol < 0 || tsCol < 0) {
            return out;
        }

        Map<String, Candidate> bestByPair = new LinkedHashMap<>();
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = parseCsvLine(line);
            if (p.length <= Math.max(itCol, tsCol)) {
                continue;
            }
            String tradingSymbol = p[tsCol].trim();
            if (tradingSymbol.isBlank()) {
                continue;
            }
            String upper = tradingSymbol.toUpperCase(Locale.ROOT);
            String pair = matchingPair(upper);
            if (pair == null) {
                continue;
            }
            String instrumentType = typeCol >= 0 && typeCol < p.length ? p[typeCol].trim().toUpperCase(Locale.ROOT) : "";
            if (!instrumentType.isBlank() && !"FUT".equals(instrumentType) && !"CUR".equals(instrumentType)) {
                continue;
            }
            int token;
            try {
                token = (int) Long.parseLong(p[itCol].trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            Candidate candidate = new Candidate(
                    token,
                    tradingSymbol,
                    parseExpiry(expiryCol >= 0 && expiryCol < p.length ? p[expiryCol].trim() : null));
            bestByPair.merge(pair, candidate, CdsInstrumentResolver::prefer);
        }

        bestByPair.forEach((pair, candidate) -> out.put(pair, candidate.token()));
        return out;
    }

    private static Candidate prefer(Candidate left, Candidate right) {
        if (left.expiry().isPresent() && right.expiry().isPresent()) {
            int byExpiry = left.expiry().get().compareTo(right.expiry().get());
            if (byExpiry != 0) {
                return byExpiry <= 0 ? left : right;
            }
        } else if (left.expiry().isPresent()) {
            return left;
        } else if (right.expiry().isPresent()) {
            return right;
        }
        return left.tradingSymbol().length() <= right.tradingSymbol().length() ? left : right;
    }

    private static Optional<LocalDate> parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            if (raw.length() >= 10) {
                return Optional.of(LocalDate.parse(raw.substring(0, 10)));
            }
            return Optional.of(LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    private static String matchingPair(String tradingSymbolUpper) {
        for (String pair : MAJOR_PAIRS) {
            if (tradingSymbolUpper.equals(pair) || tradingSymbolUpper.startsWith(pair)) {
                return pair;
            }
        }
        return null;
    }

    private static int col(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equalsIgnoreCase(header[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private static String[] parseCsvLine(String line) {
        if (line == null || line.isEmpty()) {
            return new String[0];
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        out.add(cell.toString());
        return out.toArray(new String[0]);
    }

    private record Candidate(int token, String tradingSymbol, Optional<LocalDate> expiry) {
    }
}
