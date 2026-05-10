package com.stokr.marketdata.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/marketdata")
@RequiredArgsConstructor
@Tag(name = "Market data")
public class MarketDataController {

    private final MarketDataService marketDataService;
    private final MarketDataQueryService marketDataQueryService;
    private final MarketdataCandleRepository candleRepository;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    public record TickIngestRequest(
            @NotBlank String symbol,
            @NotNull Instant tickTime,
            @NotNull BigDecimal price,
            BigDecimal quantity,
            String tradeSide,
            String source
    ) {
    }

    @Operation(summary = "Ingest a trade tick (aggregates into 1m candles)")
    @PostMapping("/ticks")
    public ApiResponse<MarketdataTick> ingest(
            @AuthenticationPrincipal StokrUserDetails user,
            @RequestBody TickIngestRequest req
    ) {
        MarketdataTick tick = new MarketdataTick();
        tick.setSymbol(req.symbol());
        tick.setTickTime(req.tickTime());
        tick.setPrice(req.price());
        tick.setQuantity(req.quantity());
        tick.setTradeSide(req.tradeSide());
        tick.setSource(req.source() != null ? req.source() : "api");
        return ApiResponse.ok(marketDataService.ingestTick(tick, zone), CorrelationIdHolder.get());
    }

    @Operation(summary = "Fetch candles from persistence")
    @GetMapping("/candles")
    public ApiResponse<List<MarketdataCandle>> candles(
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "timeframe", defaultValue = "1m") String timeframe,
            @RequestParam(value = "limit", defaultValue = "500") int limit
    ) {
        return ApiResponse.ok(marketDataQueryService.lastBarsAsc(symbol, timeframe, limit), CorrelationIdHolder.get());
    }

    @Operation(summary = "Fetch candles in a time range (replay / visualization)")
    @GetMapping("/candles/range")
    public ApiResponse<List<MarketdataCandle>> candlesRange(
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "timeframe", defaultValue = "1m") String timeframe,
            @RequestParam("start") Instant start,
            @RequestParam("end") Instant end
    ) {
        return ApiResponse.ok(marketDataQueryService.rangeAsc(symbol, timeframe, start, end), CorrelationIdHolder.get());
    }

    @Operation(summary = "Latest candle row for symbol/tf/openTime if present")
    @GetMapping("/candles/exact")
    public ApiResponse<MarketdataCandle> candleExact(
            @RequestParam("symbol") String symbol,
            @RequestParam("timeframe") String timeframe,
            @RequestParam("openTime") Instant openTime
    ) {
        return ApiResponse.ok(
                candleRepository.findBySymbolAndTimeframeAndOpenTimeAndDeletedFalse(symbol, timeframe, openTime).orElse(null),
                CorrelationIdHolder.get()
        );
    }
}
