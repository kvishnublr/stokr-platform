package com.stokr.bootstrap.feed.zerodha;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Kite Connect v3 binary quote frames (see official WebSocket docs).
 *
 * Packet sizes by mode:
 *   LTP   :  8 bytes  — token(4) + ltp(4)
 *   Quote : 44 bytes  — token(4) + ltp(4) + lastQty(4) + avgPrice(4) + volume(4) + buyQty(4) + sellQty(4) + ohlc(16)
 *   Full  : 184 bytes — quote + market depth (5 levels bid/ask)
 *
 * Index instruments have different sizes (8/28/32 bytes) — detected by packet length.
 *
 * We extract: instrumentToken, lastPrice, lastTradedQuantity (for candle volume).
 */
public final class KiteTickerBinaryParser {

    private KiteTickerBinaryParser() {
    }

    /**
     * Parsed tick with price and volume data from quote/full mode packets.
     * For LTP-mode packets (8 bytes), volume defaults to 0.
     */
    public record ParsedLtpTick(int instrumentToken, BigDecimal lastPricePaise, int lastTradedQuantity, long volumeTraded) {
        /** Backwards-compatible constructor for LTP-only mode */
        public ParsedLtpTick(int instrumentToken, BigDecimal lastPricePaise) {
            this(instrumentToken, lastPricePaise, 0, 0L);
        }
    }

    public static List<ParsedLtpTick> parseBinaryMessage(byte[] frame) {
        List<ParsedLtpTick> out = new ArrayList<>();
        if (frame == null || frame.length < 4) {
            return out;
        }
        if (frame.length == 1) {
            return out;
        }
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int offset = 0;
        int packetCount = Short.toUnsignedInt(buf.getShort(offset));
        offset += 2;
        if (packetCount <= 0 || packetCount > 5000) {
            return out;
        }
        for (int i = 0; i < packetCount && offset + 2 <= frame.length; i++) {
            int packetLen = Short.toUnsignedInt(buf.getShort(offset));
            offset += 2;
            if (packetLen <= 0 || offset + packetLen > frame.length) {
                break;
            }
            ParsedLtpTick tick = parseSinglePacket(frame, offset, packetLen);
            if (tick != null) {
                out.add(tick);
            }
            offset += packetLen;
        }
        return out;
    }

    private static ParsedLtpTick parseSinglePacket(byte[] frame, int start, int len) {
        if (len < 8) {
            return null;
        }
        ByteBuffer pkt = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int token    = pkt.getInt(start);
        int ltpPaise = pkt.getInt(start + 4);
        BigDecimal price = BigDecimal.valueOf(ltpPaise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // Quote mode (44 bytes) or Full mode (184 bytes) for non-index instruments
        // Layout: token(4) + ltp(4) + lastQty(4) + avgPrice(4) + volume(4) + ...
        if (len >= 44) {
            int lastTradedQty = pkt.getInt(start + 8);
            // avgPrice at start+12 (skip)
            int volumeTraded  = pkt.getInt(start + 16);
            return new ParsedLtpTick(token, price, lastTradedQty, Integer.toUnsignedLong(volumeTraded));
        }

        // Index quote (28 bytes): token(4) + ltp(4) + high(4) + low(4) + open(4) + close(4) + change(4)
        // No volume for indices — return with zero volume
        if (len >= 28) {
            return new ParsedLtpTick(token, price, 0, 0L);
        }

        // LTP mode (8 bytes) — no volume available
        return new ParsedLtpTick(token, price);
    }
}
