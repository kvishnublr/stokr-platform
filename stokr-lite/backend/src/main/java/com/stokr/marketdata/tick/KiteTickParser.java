package com.stokr.marketdata.tick;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses Kite Connect v3 WebSocket binary packets.
 *
 * Message structure:
 *   [0-2]   int16  number of packets
 *   [2-4]   int16  size of packet 1
 *   [4..]   bytes  packet 1 data
 *   [...]   int16  size of packet 2
 *   [...]   bytes  packet 2 data
 *
 * Packet types by size:
 *   8   — LTP (instrument_token + last_price)
 *   28  — index quote
 *   32  — index full
 *   44  — quote
 *   184 — full (includes depth)
 *
 * All prices are int32 paise values, divide by 100 (or 10000000 for CDS).
 */
@Slf4j
public class KiteTickParser {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MODE_LTP = 8;
    private static final int MODE_INDEX_QUOTE = 28;
    private static final int MODE_INDEX_FULL = 32;
    private static final int MODE_QUOTE = 44;
    private static final int MODE_FULL = 184;

    private static final int SEGMENT_CDS = 13;
    private static final int SEGMENT_BCD = 19;

    private static final Map<Integer, String> tokenToSymbol = new ConcurrentHashMap<>();

    public static void addMapping(int token, String symbol) {
        tokenToSymbol.put(token, symbol);
    }

    public static void addMappings(Map<Integer, String> map) {
        tokenToSymbol.putAll(map);
    }

    private static int parseAttempts = 0;

    public static List<TickData> parse(ByteBuffer buffer, Set<String> subscribedSymbols) {
        List<TickData> result = new ArrayList<>();
        int remaining = buffer.remaining();
        try {
            buffer.order(ByteOrder.BIG_ENDIAN);
            if (remaining < 2) {
                log.debug("Binary msg too small: {} bytes", remaining);
                return result;
            }
            int numPackets = Short.toUnsignedInt(buffer.getShort());

            for (int i = 0; i < numPackets && buffer.remaining() >= 2; i++) {
                int packetSize = Short.toUnsignedInt(buffer.getShort());
                if (packetSize <= 0 || packetSize > buffer.remaining()) break;

                int limit = buffer.limit();
                buffer.limit(buffer.position() + packetSize);

                try {
                    TickData tick = parsePacket(buffer, subscribedSymbols);
                    if (tick != null) result.add(tick);
                } catch (Exception e) {
                    log.warn("Failed to parse packet size {}: {}: {}", packetSize, e.getClass().getSimpleName(), e.getMessage());
                }

                buffer.limit(limit);
            }

            if (numPackets > 0 && result.isEmpty()) {
                log.debug("Parsed {} packets, {} ticks (none matched subscribed symbols)", numPackets, result.size());
            }
        } catch (Exception e) {
            if (parseAttempts++ < 3) {
                buffer.rewind();
                byte[] raw = new byte[Math.min(remaining, 32)];
                buffer.get(raw);
                log.warn("Binary parse error ({} bytes): {}: {} firstBytes={}", remaining, e.getClass().getSimpleName(), e.getMessage(), bytesToHex(raw));
            } else {
                log.warn("Binary parse error ({} bytes): {}: {}", remaining, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return result;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private static TickData parsePacket(ByteBuffer buf, Set<String> subscribedSymbols) {
        int startPos = buf.position();
        int packetLen = buf.remaining();

        int instrumentToken = buf.getInt();
        int segment = instrumentToken & 0xFF;

        if (packetLen == MODE_LTP) {
            int lastPrice = buf.getInt();
            String symbol = tokenToSymbol.get(instrumentToken);
            if (symbol == null) return null;
            return TickData.builder()
                .symbol(symbol)
                .exchangeTs(LocalDateTime.now(IST))
                .receivedTs(LocalDateTime.now(IST))
                .ltp(divPrice(segment, lastPrice))
                .volume(0)
                .minuteVolume(0)
                .createdAt(Instant.now())
                .build();
        }

        if (packetLen == MODE_INDEX_QUOTE && packetLen == MODE_INDEX_FULL) {
            return null;
        }

        if (packetLen == MODE_INDEX_QUOTE || packetLen == MODE_INDEX_FULL) {
            int lastPrice = buf.getInt();
            int high = buf.getInt();
            int low = buf.getInt();
            int open = buf.getInt();
            int close = buf.getInt();
            buf.getInt(); // change

            String symbol = tokenToSymbol.get(instrumentToken);
            if (symbol == null) return null;

            var tick = TickData.builder()
                .symbol(symbol)
                .exchangeTs(LocalDateTime.now(IST))
                .receivedTs(LocalDateTime.now(IST))
                .ltp(divPrice(segment, lastPrice))
                .volume(0)
                .minuteVolume(0)
                .createdAt(Instant.now())
                .build();

            if (packetLen == MODE_INDEX_FULL) {
                buf.getInt(); // timestamp
            }

            return tick;
        }

        if (packetLen == MODE_QUOTE || packetLen == MODE_FULL) {
            int lastPrice = buf.getInt();
            int lastQty = buf.getInt();
            int avgPrice = buf.getInt();
            long volume = Integer.toUnsignedLong(buf.getInt());
            long buyQty = Integer.toUnsignedLong(buf.getInt());
            long sellQty = Integer.toUnsignedLong(buf.getInt());
            int open = buf.getInt();
            int high = buf.getInt();
            int low = buf.getInt();
            int close = buf.getInt();

            String symbol = tokenToSymbol.get(instrumentToken);
            if (symbol == null || !subscribedSymbols.contains(symbol)) return null;

            var tick = TickData.builder()
                .symbol(symbol)
                .exchangeTs(LocalDateTime.now(IST))
                .receivedTs(LocalDateTime.now(IST))
                .ltp(divPrice(segment, lastPrice))
                .volume(volume)
                .minuteVolume(0)
                .buyQuantity(buyQty)
                .sellQuantity(sellQty)
                .createdAt(Instant.now())
                    .changePct(close != 0
                        ? BigDecimal.valueOf(lastPrice - close)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(close), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .build();

            if (packetLen == MODE_FULL) {
                buf.getInt(); // last_trade_timestamp
                buf.getInt(); // oi
                buf.getInt(); // oi_day_high
                buf.getInt(); // oi_day_low
                buf.getInt(); // exchange_timestamp
                // Skip depth (120 bytes = 10 entries * 12 bytes)
                int depthBytes = MODE_FULL - (buf.position() - startPos);
                if (depthBytes > 0) buf.position(buf.position() + Math.min(depthBytes, buf.remaining()));
            }

            return tick;
        }

        return null;
    }

    private static BigDecimal divPrice(int segment, int paise) {
        double divisor = 100.0;
        if (segment == SEGMENT_CDS) divisor = 10000000.0;
        else if (segment == SEGMENT_BCD) divisor = 10000.0;
        return BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
    }
}
