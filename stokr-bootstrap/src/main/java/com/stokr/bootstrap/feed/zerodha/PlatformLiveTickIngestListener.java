package com.stokr.bootstrap.feed.zerodha;

import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.marketdata.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;

/**
 * Receives platform websocket ticks and feeds them into the in-memory candle aggregator.
 * DB writes are batched by MarketDataService#flushDirtyCandles every 500ms — no per-tick DB work here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformLiveTickIngestListener {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final MarketDataService marketDataService;

    @EventListener
    public void onTick(PlatformLiveTickEvent e) {
        try {
            MarketdataTick tick = new MarketdataTick();
            tick.setSymbol(e.symbol());
            tick.setTickTime(e.tickTime());
            tick.setPrice(e.price());
            tick.setQuantity(BigDecimal.ONE);
            tick.setSource("PLATFORM_ZERODHA_WS");
            marketDataService.ingestTick(tick, MARKET_ZONE);
        } catch (Exception ex) {
            log.warn("platform.tick.ingest_failed symbol={} token={} {}", e.symbol(), e.instrumentToken(), ex.toString());
        }
    }
}
