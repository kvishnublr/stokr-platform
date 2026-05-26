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
 * Packet sizes by mode (non-index instruments):
 *   LTP   :  8 bytes  — token(4) + ltp(4)
 *   Quote : 44 bytes  — token(4) + ltp(4) + lastQty(4) + avgPrice(4) + volume(4)
 *                        + buyQty(4) + sellQty(4) + ohlc(16)
 *   Full  : 184 bytes — quote(44) + change(4) + exchTs(4) + oi(4) + oiDayHigh(4)
 *                        + oiDayLow(4) + exchTs2(4) + depth(5×12×2=120)
 *
 * Index instruments have different sizes (8/28/32 bytes) — detected by packet length.
 */
public final class KiteTickerBinaryParser {

    private KiteTickerBinaryParser() {
    }

    /**
     * Parsed tick with price, volume, and order book pressure data.
     *
     * @param instrumentToken   Kite instrument token
     * @param lastPricePaise    last traded price (already divided by 100)
     * @param lastTradedQuantity quantity of the most recent trade
     * @param volumeTraded      cumulative volume traded today
     * @param totalBuyQuantity  total pending buy order quantity (order book depth)
     * @param totalSellQuantity total pending sell order quantity (order book depth)
     */
    public record ParsedLtpTick(
            int instrumentToken,
            BigDecimal lastPricePaise,
            int lastTradedQuantity,
            long volumeTraded,
            long totalBuyQuantity,
            long totalSellQuantity
    ) {
        /** Backwards-compatible constructor for LTP-only mode */
        public ParsedLtpTick(int instrumentToken, BigDecimal lastPricePaise) {
            this(instrumentToken, lastPricePaise, 0, 0L, 0L, 0L);
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
        // Layout: token(4) + ltp(4) + lastQty(4) + avgPrice(4) + volume(4) + buyQty(4) + sellQty(4) + ohlc(16)
        if (len >= 44) {
            int lastTradedQty   = pkt.getInt(start + 8);
            // avgPrice at start+12 (skip)
            int volumeTraded    = pkt.getInt(start + 16);
            int totalBuyQty     = pkt.getInt(start + 20);
            int totalSellQty    = pkt.getInt(start + 24);
            return new ParsedLtpTick(
                    token, price, lastTradedQty,
                    Integer.toUnsignedLong(volumeTraded),
                    Integer.toUnsignedLong(totalBuyQty),
                    Integer.toUnsignedLong(totalSellQty)
            );
        }

        // Index quote (28 bytes): no volume/buyQty/sellQty for indices
        if (len >= 28) {
            return new ParsedLtpTick(token, price, 0, 0L, 0L, 0L);
        }

        // LTP mode (8 bytes) — no volume available
        return new ParsedLtpTick(token, price);
    }
}
