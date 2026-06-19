package com.stokr.marketdata.chartink;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/chartink")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Chartink")
public class ChartinkWebhookController {

    private final ChartinkAlertParser alertParser;
    private final ChartinkAlertStore alertStore;
    private final JdbcTemplate jdbcTemplate;

    @Operation(summary = "Receive Chartink scanner webhook alerts")
    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> webhook(@RequestBody String rawPayload) {
        log.info("chartink.webhook.received payload_length={}", rawPayload.length());

        try {
            ChartinkAlert alert = alertParser.parse(rawPayload);
            List<ChartinkAlert.StockAlert> stockAlerts = alertParser.toStockAlerts(alert);

            log.info("chartink.webhook.parsed scan={} stocks={} count={}",
                    alert.scanName(), stockAlerts.stream().map(ChartinkAlert.StockAlert::symbol).toList(),
                    stockAlerts.size());

            // Store in memory for reference
            for (ChartinkAlert.StockAlert sa : stockAlerts) {
                alertStore.put(sa.symbol(), sa.scanName(), sa.triggerPrice().doubleValue(), Instant.now());
                log.info("chartink.webhook.stock symbol={} price={} scan={}",
                        sa.symbol(), sa.triggerPrice(), sa.scanName());
            }

            // Insert signals directly into database for immediate availability
            insertSignalsFromAlert(alert);

            return ApiResponse.ok(CorrelationIdHolder.get());
        } catch (Exception e) {
            log.error("chartink.webhook.error", e);
            return ApiResponse.ok(CorrelationIdHolder.get());
        }
    }

    private void insertSignalsFromAlert(ChartinkAlert alert) {
        log.info("chartink.signal_inserter.called alert={}", alert.scanName());
        try {
            List<String> symbols = alert.stocks();
            List<BigDecimal> prices = alert.triggerPrices();

            log.info("chartink.signal_insert.processing alert={} symbols={} count={}",
                    alert.scanName(), symbols, symbols.size());

            for (int i = 0; i < symbols.size(); i++) {
                String symbol = symbols.get(i);
                BigDecimal price = prices.get(i);

                log.info("chartink.signal_insert.processing_symbol symbol={} price={}", symbol, price);
                insertEquitySignal(symbol, price, alert.scanName(), alert.triggeredAt());
            }
            log.info("chartink.signal_insert.completed alert={} count={}", alert.scanName(), symbols.size());
        } catch (Exception e) {
            log.error("chartink.signal_insert.error alert={}", alert.scanName(), e);
        }
    }

    private void insertEquitySignal(String symbol, BigDecimal entryPrice, String scanName, Instant detectedAt) {
        String sql = "INSERT INTO equity_signals (" +
                "symbol_name, sector, tier, direction, current_price, entry_level, " +
                "stop_loss_level, target_level1, confidence_level, quality_score, " +
                "execution_status, time_detected, is_active, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try {
            BigDecimal stopLoss = entryPrice.multiply(BigDecimal.valueOf(0.98));
            BigDecimal target1 = entryPrice.multiply(BigDecimal.valueOf(1.02));

            jdbcTemplate.update(sql,
                    symbol,
                    "TECHNOLOGY",
                    "TIER1",
                    "BUY",
                    entryPrice,
                    entryPrice,
                    stopLoss,
                    target1,
                    85,
                    90.0,
                    "PENDING",
                    new Timestamp(detectedAt.toEpochMilli()),
                    true,
                    new Timestamp(System.currentTimeMillis())
            );

            log.info("chartink.signal_inserted symbol={} price={} scan={}", symbol, entryPrice, scanName);
        } catch (Exception e) {
            log.error("chartink.signal_insert.failed symbol={} price={}", symbol, entryPrice, e);
        }
    }
}
