package com.stokr.strategy;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class StaticUniverseSyncService implements ApplicationRunner {

    private final UniverseGroupRepository groupRepository;
    private final UniverseSymbolRepository symbolRepository;
    private final EntityManager entityManager;

    private static final Map<String, List<String>> STATIC_UNIVERSES = new LinkedHashMap<>();

    static {
        STATIC_UNIVERSES.put("NIFTY_50", List.of(
            "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","SBIN","BHARTIARTL","ITC","KOTAKBANK","LT",
            "HINDUNILVR","AXISBANK","MARUTI","BAJFINANCE","ASIANPAINT","SUNPHARMA","TITAN","ULTRACEMCO",
            "WIPRO","HCLTECH","TATAMOTORS","ONGC","NTPC","POWERGRID","ADANIPORTS","JSWSTEEL","TATASTEEL",
            "COALINDIA","M&M","TECHM","ADANIENT","GRASIM","BAJAJFINSV","CIPLA","NESTLEIND","DRREDDY",
            "DIVISLAB","APOLLOHOSP","EICHERMOT","BRITANNIA","HEROMOTOCO","BPCL","INDUSINDBK","HDFCLIFE",
            "SBILIFE","TATACONSUM","UPL","MCDOWELL","HINDALCO","TATASTEEL"
        ));
        STATIC_UNIVERSES.put("NIFTY_100", List.of(
            // Nifty 50
            "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","SBIN","BHARTIARTL","ITC","KOTAKBANK","LT",
            "HINDUNILVR","AXISBANK","MARUTI","BAJFINANCE","ASIANPAINT","SUNPHARMA","TITAN","ULTRACEMCO",
            "WIPRO","HCLTECH","TATAMOTORS","ONGC","NTPC","POWERGRID","ADANIPORTS","JSWSTEEL","TATASTEEL",
            "COALINDIA","M&M","TECHM","ADANIENT","GRASIM","BAJAJFINSV","CIPLA","NESTLEIND","DRREDDY",
            "DIVISLAB","APOLLOHOSP","EICHERMOT","BRITANNIA","HEROMOTOCO","BPCL","INDUSINDBK","HDFCLIFE",
            "SBILIFE","TATACONSUM","UPL","MCDOWELL","HINDALCO","BAJAJ-AUTO",
            // Nifty Next 50
            "PIDILITIND","SIEMENS","DABUR","GODREJCP","HAVELLS","BERGEPAINT","DMART","TRENT","IRCTC",
            "ZOMATO","POLYCAB","HAL","LICI","IOB","CANBK",
            "ABB","AMBUJACEM","AUROPHARMA","BANKBARODA","BEL","BOSCHLTD","CHOLAFIN","COLPAL","DLF",
            "GAIL","GODREJPROP","ICICIPRULI","INDHOTEL","IOC","JIOFIN","LTIMINDTREE","LUPIN","MAXHEALTH",
            "NAUKRI","NHPC","OFSS","PAGEIND","PFC","RECLTD","SHRIRAMFIN","SRF","TATAELXSI","TATAPOWER",
            "TORNTPHARM","TVSMOTOR","VEDL","ZYDUSLIFE","ATGL","TIINDIA","ADANIGREEN"
        ));
        STATIC_UNIVERSES.put("NIFTY_200", List.of(
            "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","SBIN","BHARTIARTL","ITC","KOTAKBANK","LT",
            "HINDUNILVR","AXISBANK","MARUTI","BAJFINANCE","ASIANPAINT","SUNPHARMA","TITAN","ULTRACEMCO",
            "WIPRO","HCLTECH","TATAMOTORS","ONGC","NTPC","POWERGRID","ADANIPORTS","JSWSTEEL","TATASTEEL",
            "COALINDIA","M&M","TECHM","ADANIENT","GRASIM","BAJAJFINSV","CIPLA","NESTLEIND","DRREDDY",
            "DIVISLAB","APOLLOHOSP","EICHERMOT","BRITANNIA","HEROMOTOCO","BPCL","INDUSINDBK","HDFCLIFE",
            "SBILIFE","TATACONSUM","UPL","MCDOWELL","HINDALCO","PIDILITIND","SIEMENS","DABUR","GODREJCP",
            "HAVELLS","BERGEPAINT","DMART","TRENT","IRCTC","ZOMATO","POLYCAB","HAL","LICI","IOB","CANBK",
            "BEL","PFC","RECLTD","GAIL","IDEA","BANKBARODA","UNIONBANK","JINDALSTEL","VEDL","MOTHERSON",
            "AMBUJACEM","SHREECEM","CHOLAFIN","YESBANK","BOSCHLTD","SRF","TORNTPHARM","LUPIN","COLPAL"
        ));
        STATIC_UNIVERSES.put("NIFTY_500", List.of(
            "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","SBIN","BHARTIARTL","ITC","KOTAKBANK","LT",
            "HINDUNILVR","AXISBANK","MARUTI","BAJFINANCE","ASIANPAINT","SUNPHARMA","TITAN","ULTRACEMCO",
            "WIPRO","HCLTECH","TATAMOTORS","ONGC","NTPC","POWERGRID","ADANIPORTS","JSWSTEEL","TATASTEEL",
            "COALINDIA","M&M","TECHM","ADANIENT","GRASIM","BAJAJFINSV","CIPLA","NESTLEIND","DRREDDY",
            "DIVISLAB","APOLLOHOSP","EICHERMOT","BRITANNIA","HEROMOTOCO","BPCL","INDUSINDBK","HDFCLIFE",
            "SBILIFE","TATACONSUM","UPL","MCDOWELL","HINDALCO","PIDILITIND","SIEMENS","DABUR","GODREJCP",
            "HAVELLS","BERGEPAINT","DMART","TRENT","IRCTC","ZOMATO","POLYCAB","HAL","LICI","IOB","CANBK",
            "BEL","PFC","RECLTD","GAIL","IDEA","BANKBARODA","UNIONBANK","JINDALSTEL","VEDL","MOTHERSON",
            "AMBUJACEM","SHREECEM","CHOLAFIN","YESBANK","BOSCHLTD","SRF","TORNTPHARM","LUPIN","COLPAL",
            "ASHOKLEY","BANDHANBNK","PERSISTENT","LTIM","LTTS","PAGEIND","MPHASIS","NAUKRI","INDIGO",
            "DLF","MAXHEALTH","TATAPOWER","ACC","AARTIIND","AIAENG","AJANTPHARM","ALKEM","APARINDS"
        ));
        STATIC_UNIVERSES.put("BANKNIFTY", List.of(
            "HDFCBANK","ICICIBANK","AXISBANK","SBIN","KOTAKBANK","INDUSINDBK","BANKBARODA","PNB",
            "CANBK","UNIONBANK","FEDERALBNK","IDFCFIRSTB","AUBANK","BANDHANBNK","RBLBANK"
        ));
        STATIC_UNIVERSES.put("FINNIFTY", List.of(
            "HDFCBANK","ICICIBANK","KOTAKBANK","AXISBANK","BAJFINANCE","BAJAJFINSV","SBIN","HDFCLIFE",
            "SBILIFE","ICICIPRULI","CHOLAFIN","PFC","RECLTD","LICHSGFIN","SHRIRAMFIN"
        ));
        STATIC_UNIVERSES.put("AUTO_NIFTY", List.of(
            "MARUTI","TATAMOTORS","M&M","EICHERMOT","HEROMOTOCO","BAJAJAUTO","TVSMOTOR","ASHOKLEY",
            "MRF","BHARATFORG"
        ));
        STATIC_UNIVERSES.put("IT_NIFTY", List.of(
            "TCS","INFY","HCLTECH","WIPRO","TECHM","LTIM","LTTS","MPHASIS","PERSISTENT","OFSS"
        ));
        STATIC_UNIVERSES.put("PHARMA_NIFTY", List.of(
            "SUNPHARMA","DRREDDY","CIPLA","DIVISLAB","LUPIN","TORNTPHARM","AUROPHARMA","ALKEM",
            "BIOCON","ZYDUSLIFE"
        ));
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (Map.Entry<String, List<String>> entry : STATIC_UNIVERSES.entrySet()) {
            syncGroup(entry.getKey(), entry.getValue());
        }
        log.info("Static universe sync completed. {} groups synced.", STATIC_UNIVERSES.size());
    }

    @Transactional
    public void syncGroup(String groupKey) {
        List<String> symbols = STATIC_UNIVERSES.get(groupKey);
        if (symbols == null) {
            throw new IllegalArgumentException("Unknown static universe: " + groupKey);
        }
        syncGroup(groupKey, symbols);
    }

    private void syncGroup(String groupKey, List<String> symbols) {
        UniverseGroup group = groupRepository.findByGroupKey(groupKey)
                .orElseGet(() -> {
                    UniverseGroup g = UniverseGroup.builder()
                            .groupKey(groupKey)
                            .displayName(groupKey.replace("_", " "))
                            .universeType("INDEX_CONSTITUENTS")
                            .exchange("NSE")
                            .assetClass("EQUITY")
                            .segment("NSE")
                            .instrumentType("EQ")
                            .autoManaged(true)
                            .enabled(true)
                            .build();
                    return groupRepository.save(g);
                });

        symbolRepository.deleteByGroupId(group.getId());
        entityManager.clear();
        for (String sym : symbols) {
            UniverseSymbol s = UniverseSymbol.builder()
                    .groupId(group.getId())
                    .symbol(sym)
                    .tradingSymbol(sym)
                    .exchange("NSE")
                    .instrumentType("EQ")
                    .enabled(true)
                    .build();
            symbolRepository.save(s);
        }
        log.info("Synced {} symbols into group {}", symbols.size(), groupKey);
    }

    public Set<String> getAvailableKeys() {
        return Collections.unmodifiableSet(STATIC_UNIVERSES.keySet());
    }
}
