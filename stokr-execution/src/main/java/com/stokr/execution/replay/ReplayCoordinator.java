package com.stokr.execution.replay;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Coordinates historical market data replay.
 * Manages time progression, speed control, and event emission.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplayCoordinator {

    private final MarketdataCandleRepository candleRepository;

    private Instant currentTime;
    private Instant replayStartTime;
    private Instant replayEndTime;
    private double replaySpeed = 1.0;
    private boolean isPaused = false;
    private boolean isRunning = false;
    private List<Consumer<Instant>> tickSubscribers = new ArrayList<>();

    /**
     * Start replaying historical data.
     *
     * @param symbol Instrument to replay
     * @param startTime Start timestamp
     * @param endTime End timestamp
     * @param speed Replay speed (1.0 = normal, 2.0 = 2x, etc)
     */
    public void startReplay(String symbol, Instant startTime, Instant endTime, double speed) {
        this.replayStartTime = startTime;
        this.replayEndTime = endTime;
        this.currentTime = startTime;
        this.replaySpeed = speed;
        this.isRunning = true;
        this.isPaused = false;

        log.info("replay.start symbol={} start={} end={} speed={}x",
                symbol, startTime, endTime, speed);

        new Thread(() -> executeReplay(symbol)).start();
    }

    /**
     * Pause replay.
     */
    public void pause() {
        isPaused = true;
        log.info("replay.paused currentTime={}", currentTime);
    }

    /**
     * Resume replay.
     */
    public void resume() {
        isPaused = false;
        log.info("replay.resumed currentTime={}", currentTime);
    }

    /**
     * Stop replay.
     */
    public void stop() {
        isRunning = false;
        log.info("replay.stopped currentTime={}", currentTime);
    }

    /**
     * Jump to specific time.
     */
    public void jumpToTime(Instant targetTime) {
        currentTime = targetTime;
        log.info("replay.jump targetTime={}", targetTime);
    }

    /**
     * Rewind N seconds.
     */
    public void rewind(long seconds) {
        currentTime = currentTime.minusSeconds(seconds);
        log.info("replay.rewind seconds={} newTime={}", seconds, currentTime);
    }

    /**
     * Subscribe to tick emissions.
     */
    public void onTick(Consumer<Instant> subscriber) {
        tickSubscribers.add(subscriber);
    }

    /**
     * Get current replay time.
     */
    public Instant getCurrentTime() {
        return currentTime;
    }

    /**
     * Get replay progress (0.0 to 1.0).
     */
    public double getProgress() {
        if (replayStartTime == null || replayEndTime == null) {
            return 0.0;
        }
        long total = replayEndTime.toEpochMilli() - replayStartTime.toEpochMilli();
        long elapsed = currentTime.toEpochMilli() - replayStartTime.toEpochMilli();
        return total > 0 ? (double) elapsed / total : 0.0;
    }

    /**
     * Execute replay loop.
     */
    private void executeReplay(String symbol) {
        // Load candles for replay period
        List<MarketdataCandle> candles = candleRepository
                .findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                        symbol, "1m", replayStartTime.minusSeconds(300), replayEndTime.plusSeconds(300)
                );

        log.info("replay.loaded symbol={} candles={}", symbol, candles.size());

        for (MarketdataCandle candle : candles) {
            if (!isRunning) {
                break;
            }

            // Wait if paused
            while (isPaused && isRunning) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Stop if we've reached end time
            if (candle.getOpenTime().isAfter(replayEndTime)) {
                stop();
                break;
            }

            currentTime = candle.getOpenTime();

            // Calculate sleep time based on speed
            long actualSleepMs = Math.round(1000.0 / replaySpeed);  // 1 second per candle, scaled by speed
            try {
                Thread.sleep(actualSleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            // Emit tick event to all subscribers
            for (Consumer<Instant> subscriber : tickSubscribers) {
                try {
                    subscriber.accept(currentTime);
                } catch (Exception ex) {
                    log.warn("replay.subscriber.error {}", ex.getMessage());
                }
            }
        }

        isRunning = false;
        log.info("replay.complete currentTime={}", currentTime);
    }

    /**
     * Get replay state.
     */
    public ReplayState getState() {
        return new ReplayState(
                currentTime,
                replayStartTime,
                replayEndTime,
                replaySpeed,
                isPaused,
                isRunning,
                getProgress()
        );
    }

    /**
     * Replay state snapshot.
     */
    public record ReplayState(
            Instant currentTime,
            Instant startTime,
            Instant endTime,
            double speed,
            boolean paused,
            boolean running,
            double progress
    ) {}
}
