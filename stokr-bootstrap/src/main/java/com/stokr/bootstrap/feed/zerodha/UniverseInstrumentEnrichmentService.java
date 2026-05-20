package com.stokr.bootstrap.feed.zerodha;

import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Auto-fills missing broker fields (trading_symbol, instrument_token)
 * in strategy_universe_symbols using the latest Zerodha instrument map.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UniverseInstrumentEnrichmentService {

    private final StrategyUniverseSymbolRepository symbolRepository;

    @Transactional
    public int enrichMbxUniverseSymbols(Map<String, Integer> symbolToToken) {
        if (symbolToToken == null || symbolToToken.isEmpty()) {
            return 0;
        }

        List<StrategyUniverseSymbol> rows = symbolRepository.findAllByEnabledTrueAndExchangeIgnoreCase("MCX");
        if (rows.isEmpty()) {
            return 0;
        }

        int updated = 0;
        for (StrategyUniverseSymbol row : rows) {
            String canonical = normalize(row.getSymbol());
            if (canonical.isBlank() || canonical.startsWith("MCX_")) {
                continue;
            }

            boolean needsToken = row.getInstrumentToken() == null || row.getInstrumentToken() <= 0;
            boolean needsTrading = row.getTradingSymbol() == null || row.getTradingSymbol().isBlank();
            if (!needsToken && !needsTrading) {
                continue;
            }

            String bestTrading = chooseBestTradingSymbol(canonical, symbolToToken);
            if (bestTrading == null) {
                continue;
            }
            Integer token = symbolToToken.get(bestTrading);
            if (token == null || token <= 0) {
                continue;
            }

            if (needsTrading) {
                row.setTradingSymbol(bestTrading);
            }
            if (needsToken) {
                row.setInstrumentToken(token.longValue());
            }
            updated++;
        }

        if (updated > 0) {
            symbolRepository.saveAll(rows);
            log.info("universe.instrument.enriched exchange=MCX updated={}", updated);
        }
        return updated;
    }

    private static String chooseBestTradingSymbol(String canonical, Map<String, Integer> symbolToToken) {
        String exact = symbolToToken.keySet().stream()
                .filter(k -> normalize(k).equals(canonical))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }

        return symbolToToken.keySet().stream()
                .filter(k -> normalize(k).startsWith(canonical))
                .min(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
