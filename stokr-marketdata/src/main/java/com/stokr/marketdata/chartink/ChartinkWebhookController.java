package com.stokr.marketdata.chartink;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final ChartinkSignalInserter signalInserter;

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
            signalInserter.insertSignalsFromAlert(alert);

            return ApiResponse.ok(CorrelationIdHolder.get());
        } catch (Exception e) {
            log.error("chartink.webhook.error", e);
            return ApiResponse.ok(CorrelationIdHolder.get());
        }
    }
}
