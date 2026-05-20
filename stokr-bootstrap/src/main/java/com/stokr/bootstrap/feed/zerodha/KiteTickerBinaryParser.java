package com.stokr.bootstrap.feed.zerodha;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Kite Connect v3 binary quote frames (see official WebSocket docs). Supports LTP packets (8 bytes) and longer quote/full modes
 * by reading token + last traded price from the leading fields.
 */
public final class KiteTickerBinaryParser {

    private KiteTickerBinaryParser() {
    }

    public record ParsedLtpTick(int instrumentToken, BigDecimal lastPricePaise) {
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
        // Use absolute offsets on the full frame — wrap(frame, start, len) sets position=start
        // but getInt(n) is an absolute index, so we must add start explicitly.
        ByteBuffer pkt = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int token    = pkt.getInt(start);
        int ltpPaise = pkt.getInt(start + 4);
        BigDecimal price = BigDecimal.valueOf(ltpPaise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new ParsedLtpTick(token, price);
    }
}
