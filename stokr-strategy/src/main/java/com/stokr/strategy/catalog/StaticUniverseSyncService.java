package com.stokr.strategy.catalog;

import com.stokr.strategy.domain.StrategyUniverseGroup;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyUniverseGroupRepository;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default UniverseSyncService backed by an in-memory static map.
 * Replace or extend this with a CSV reader or NSE API integration
 * when live data sources are available — the interface stays stable.
 *
 * Currently seeds NIFTY_50, BANKNIFTY, FINNIFTY with well-known constituents.
 * MCX Bullion/Energy are pre-seeded by the V31 migration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaticUniverseSyncService implements UniverseSyncService {

    private final StrategyUniverseGroupRepository groupRepository;
    private final StrategyUniverseSymbolRepository symbolRepository;

    /** Static symbol lists — update these or replace with a CSV/API source */
    private static final Map<String, List<String>> STATIC_SYMBOLS = Map.of(
        "NIFTY_50", List.of(
            "RELIANCE","TCS","HDFCBANK","ICICIBANK","HINDUNILVR","INFY","ITC","SBIN",
            "BHARTIARTL","AXISBANK","KOTAKBANK","LT","BAJFINANCE","HCLTECH","ASIANPAINT",
            "MARUTI","TITAN","NESTLEIND","ULTRACEMCO","WIPRO","ONGC","JSWSTEEL","NTPC",
            "POWERGRID","TECHM","TATASTEEL","BAJAJFINSV","COALINDIA","HDFCLIFE","ADANIPORTS",
            "BAJAJ-AUTO","M&M","APOLLOHOSP","SUNPHARMA","CIPLA","DIVISLAB","GRASIM",
            "BPCL","TATACONSUM","DRREDDY","INDUSINDBK","EICHERMOT","HEROMOTOCO","BRITANNIA",
            "UPL","SBILIFE","SHREECEM","HDFC","LTIM"
        ),
        "BANKNIFTY_FUTURES", List.of(
            "HDFCBANK","ICICIBANK","SBIN","AXISBANK","KOTAKBANK","INDUSINDBK",
            "BANDHANBNK","FEDERALBNK","IDFCFIRSTB","PNB"
        ),
        "FINNIFTY_FUTURES", List.of(
            "HDFCBANK","ICICIBANK","SBIN","AXISBANK","KOTAKBANK","BAJFINANCE",
            "HDFCLIFE","SBILIFE","BAJAJFINSV","INDUSINDBK","SHRIRAMFIN","MUTHOOTFIN"
        )
    );

    @Override
    public List<String> supportedGroupKeys() {
        return List.of("NIFTY_50", "BANKNIFTY_FUTURES", "FINNIFTY_FUTURES");
    }

    @Override
    @Transactional
    public int sync(String groupKey) {
        List<String> symbols = STATIC_SYMBOLS.get(groupKey);
        if (symbols == null || symbols.isEmpty()) {
            log.warn("universe.sync.no_static_data groupKey={}", groupKey);
            return 0;
        }
        Optional<StrategyUniverseGroup> groupOpt = groupRepository.findByGroupKey(groupKey);
        if (groupOpt.isEmpty()) {
            log.warn("universe.sync.group_not_found groupKey={}", groupKey);
            return 0;
        }
        StrategyUniverseGroup group = groupOpt.get();
        symbolRepository.deleteAllByGroupId(group.getId());

        int count = 0;
        for (String symbol : symbols) {
            StrategyUniverseSymbol s = new StrategyUniverseSymbol();
            s.setGroup(group);
            s.setSymbol(symbol);
            s.setTradingSymbol(symbol);
            s.setExchange(group.getExchange());
            s.setInstrumentType(group.getInstrumentType());
            s.setEnabled(true);
            symbolRepository.save(s);
            count++;
        }
        log.info("universe.sync.done groupKey={} symbols={}", groupKey, count);
        return count;
    }
}
