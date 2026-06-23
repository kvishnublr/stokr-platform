package com.stokr.external;

import com.stokr.engine.CandleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

@Slf4j
@Service
public class YahooFinanceCandleService {

    private final RestTemplate restTemplate = new RestTemplate();

    public List<CandleData> fetchCandles(String symbol, String timeframe, Instant startTime, Instant endTime) {
        log.info("Fetching candles from Yahoo Finance: symbol={}, timeframe={}, start={}, end={}",
            symbol, timeframe, startTime, endTime);

        try {
            String yahooSymbol = symbol + ".NS";
            long period1 = startTime.getEpochSecond();
            long period2 = endTime.getEpochSecond();
            String interval = mapInterval(timeframe);

            String url = String.format(
                "https://query1.finance.yahoo.com/v8/finance/chart/%s?period1=%d&period2=%d&interval=%s",
                yahooSymbol, period1, period2, interval
            );

            log.info("Yahoo Finance URL: {}", url);

            String json = restTemplate.getForObject(url, String.class);
            if (json == null || json.contains("\"timestamp\":null")) {
                log.warn("No data returned from Yahoo Finance for {}", yahooSymbol);
                return Collections.emptyList();
            }

            return parseYahooResponse(json, symbol, timeframe);

        } catch (Exception e) {
            log.error("Failed to fetch candles from Yahoo Finance: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<CandleData> parseYahooResponse(String json, String symbol, String timeframe) {
        List<CandleData> candles = new ArrayList<>();

        try {
            timestamps:
            while (true) {
                String tsKey = "\"timestamp\":[";
                int tsStart = json.indexOf(tsKey);
                if (tsStart < 0) break;
                tsStart += tsKey.length();
                int tsEnd = json.indexOf("]", tsStart);

                String oKey = "\"open\":[";
                int oStart = json.indexOf(oKey);
                if (oStart < 0) break;
                oStart += oKey.length();
                int oEnd = json.indexOf("]", oStart);

                String hKey = "\"high\":[";
                int hStart = json.indexOf(hKey);
                if (hStart < 0) break;
                hStart += hKey.length();
                int hEnd = json.indexOf("]", hStart);

                String lKey = "\"low\":[";
                int lStart = json.indexOf(lKey);
                if (lStart < 0) break;
                lStart += lKey.length();
                int lEnd = json.indexOf("]", lStart);

                String cKey = "\"close\":[";
                int cStart = json.indexOf(cKey);
                if (cStart < 0) break;
                cStart += cKey.length();
                int cEnd = json.indexOf("]", cStart);

                String vKey = "\"volume\":[";
                int vStart = json.indexOf(vKey);
                if (vStart < 0) break;
                vStart += vKey.length();
                int vEnd = json.indexOf("]", vStart);

                String[] timestamps = json.substring(tsStart, tsEnd).split(",");
                String[] opens = json.substring(oStart, oEnd).split(",");
                String[] highs = json.substring(hStart, hEnd).split(",");
                String[] lows = json.substring(lStart, lEnd).split(",");
                String[] closes = json.substring(cStart, cEnd).split(",");
                String[] volumes = json.substring(vStart, vEnd).split(",");

                int count = Math.min(timestamps.length, closes.length);

                for (int i = 0; i < count; i++) {
                    try {
                        long ts = Long.parseLong(timestamps[i].trim());
                        BigDecimal open = new BigDecimal(opens[i].trim());
                        BigDecimal high = new BigDecimal(highs[i].trim());
                        BigDecimal low = new BigDecimal(lows[i].trim());
                        BigDecimal close = new BigDecimal(closes[i].trim());
                        long volume = (long) Double.parseDouble(volumes[i].trim());

                        CandleData candle = new CandleData();
                        candle.setSymbol(symbol);
                        candle.setTimeframe(timeframe);
                        candle.setTimestamp(Instant.ofEpochSecond(ts));
                        candle.setOpen(open);
                        candle.setHigh(high);
                        candle.setLow(low);
                        candle.setClose(close);
                        candle.setVolume(volume);

                        candles.add(candle);
                    } catch (Exception e) {
                        log.warn("Failed to parse candle at index {}: {}", i, e.getMessage());
                    }
                }
                break;
            }

            log.info("Parsed {} candles from Yahoo Finance for {}", candles.size(), symbol);

        } catch (Exception e) {
            log.error("Failed to parse Yahoo Finance response: {}", e.getMessage());
        }

        return candles;
    }

    private String mapInterval(String timeframe) {
        return switch (timeframe) {
            case "1min" -> "1m";
            case "5min" -> "5m";
            case "15min" -> "15m";
            case "hourly" -> "60m";
            case "daily" -> "1d";
            case "weekly" -> "1wk";
            default -> "1d";
        };
    }
}
