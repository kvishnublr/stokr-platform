package com.stokr.backtest.execution;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.common.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Ordered replay stages (logging + extension hooks). Keeps the candle loop thin.
 */
@Slf4j
public final class BacktestExecutionStages {

    private final UUID runId;
    private final String correlationId;
    private long stageNanos;

    public BacktestExecutionStages(UUID runId, String correlationId) {
        this.runId = runId;
        this.correlationId = correlationId;
    }

    public void candleValidated(MarketdataCandle bar) {
        Instant t0 = Instant.now();
        if (bar.getOpenTime() == null) {
            throw new BadRequestException("Candle open time is required");
        }
        if (bar.getOpenPrice() == null || bar.getHighPrice() == null || bar.getLowPrice() == null || bar.getClosePrice() == null) {
            throw new BadRequestException("Candle OHLC is incomplete");
        }
        if (bar.getClosePrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Candle close must be positive");
        }
        stageNanos += java.time.Duration.between(t0, Instant.now()).toNanos();
        log.trace("backtest.stage.candle_validated runId={} correlationId={} open={}", runId, correlationId, bar.getOpenTime());
    }

    public void indicatorWarmupSkipped(String reason) {
        log.trace("backtest.stage.indicator_warmup runId={} correlationId={} detail={}", runId, correlationId, reason);
    }

    public void signalEvaluated(boolean produced, int barIndex) {
        log.trace("backtest.stage.signal_eval runId={} correlationId={} barIndex={} produced={}", runId, correlationId, barIndex, produced);
    }

    public void riskChecked(boolean allowed) {
        log.trace("backtest.stage.risk runId={} correlationId={} allowed={}", runId, correlationId, allowed);
    }

    public void orderSimulated(String detail) {
        log.trace("backtest.stage.order_sim runId={} correlationId={} {}", runId, correlationId, detail);
    }

    public void fillModeled(String detail) {
        log.trace("backtest.stage.fill_model runId={} correlationId={} {}", runId, correlationId, detail);
    }

    public void timingSummary(int barsProcessed) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("backtest.stage.timing runId={} correlationId={} barsProcessed={} trackedStageNanos={}",
                runId, correlationId, barsProcessed, stageNanos);
    }
}
