package com.stokr.strategy.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyEvaluationScheduler {

    private final MeanReversionEvaluationService evaluationService;

    @Value("${stokr.strategy.symbols:NIFTY_FUT,BANKNIFTY_FUT}")
    private String symbolsCsv;

    @Scheduled(fixedDelayString = "${stokr.strategy.poll-ms:60000}")
    public void poll() {
        List<String> symbols = Arrays.stream(symbolsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String symbol : symbols) {
            try {
                evaluationService.evaluateSymbol(symbol, null);
            } catch (Exception ex) {
                log.warn("strategy.poll.failed symbol={}", symbol, ex);
            }
        }
    }
}
