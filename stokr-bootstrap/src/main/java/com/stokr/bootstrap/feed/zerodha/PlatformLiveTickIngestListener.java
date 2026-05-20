package com.stokr.bootstrap.feed.zerodha;

import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.marketdata.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Receives platform websocket ticks and feeds them into the in-memory candle aggregator.
 * DB writes are batched by MarketDataService#flushDirtyCandles every 500ms — no per-tick DB work here.
 *
 * Exchange-aware market hours gate (IST):
 *   NSE equities / futures : 09:15 – 15:30
 *   MCX commodity futures  : 09:00 – 23:30
 * Ticks outside the relevant session window are silently dropped.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformLiveTickIngestListener {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // MCX commodity symbol prefixes — used to route to the longer evening session
    private static final Set<String> MCX_PREFIXES = Set.of(
            "GOLD", "SILVER", "CRUDEOIL", "NATURALGAS", "COPPER",
            "ZINC", "NICKEL", "ALUMINIUM", "LEAD", "COTTON",
            "MENTHA", "CARDAMOM", "CASTORSEED", "PEPPER"
    );

    private final MarketDataService marketDataService;

    @Value("${stokr.marketdata.session.nse.start:09:15}")
    private LocalTime nseStart;

    @Value("${stokr.marketdata.session.nse.end:15:30}")
    private LocalTime nseEnd;

    @Value("${stokr.marketdata.session.mcx.start:09:00}")
    private LocalTime mcxStart;

    @Value("${stokr.marketdata.session.mcx.end:23:30}")
    private LocalTime mcxEnd;

    @EventListener
    public void onTick(PlatformLiveTickEvent e) {
        try {
            ZonedDateTime tickIst = e.tickTime().atZone(IST);
            LocalTime lt = tickIst.toLocalTime();

            boolean mcx = isMcxSymbol(e.symbol());
            LocalTime sessionStart = mcx ? mcxStart : nseStart;
            LocalTime sessionEnd   = mcx ? mcxEnd   : nseEnd;

            if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
                log.trace("tick.dropped_outside_session symbol={} time={} session={}-{}",
                        e.symbol(), lt, sessionStart, sessionEnd);
                return;
            }

            MarketdataTick tick = new MarketdataTick();
            tick.setSymbol(e.symbol());
            tick.setTickTime(e.tickTime());
            tick.setPrice(e.price());
            tick.setQuantity(BigDecimal.ONE);
            tick.setSource("PLATFORM_ZERODHA_WS");
            marketDataService.ingestTick(tick, IST);
        } catch (Exception ex) {
            log.warn("platform.tick.ingest_failed symbol={} token={} {}",
                    e.symbol(), e.instrumentToken(), ex.toString());
        }
    }

    private static boolean isMcxSymbol(String symbol) {
        if (symbol == null) return false;
        String upper = symbol.toUpperCase();
        for (String prefix : MCX_PREFIXES) {
            if (upper.startsWith(prefix)) return true;
        }
        return false;
    }
}
