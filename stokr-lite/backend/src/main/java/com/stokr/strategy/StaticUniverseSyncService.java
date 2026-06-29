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
            // Nifty 50
            "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","SBIN","BHARTIARTL","ITC","KOTAKBANK","LT",
            "HINDUNILVR","AXISBANK","MARUTI","BAJFINANCE","ASIANPAINT","SUNPHARMA","TITAN","ULTRACEMCO",
            "WIPRO","HCLTECH","TATAMOTORS","ONGC","NTPC","POWERGRID","ADANIPORTS","JSWSTEEL","TATASTEEL",
            "COALINDIA","M&M","TECHM","ADANIENT","GRASIM","BAJAJFINSV","CIPLA","NESTLEIND","DRREDDY",
            "DIVISLAB","APOLLOHOSP","EICHERMOT","BRITANNIA","HEROMOTOCO","BPCL","INDUSINDBK","HDFCLIFE",
            "SBILIFE","TATACONSUM","UPL","MCDOWELL","HINDALCO","BAJAJAUTO",
            // Nifty Next 50
            "PIDILITIND","SIEMENS","DABUR","GODREJCP","HAVELLS","BERGEPAINT","DMART","TRENT","IRCTC",
            "ZOMATO","POLYCAB","HAL","LICI","IOB","CANBK","BEL","PFC","RECLTD","GAIL","IDEA",
            "BANKBARODA","UNIONBANK","JINDALSTEL","VEDL","MOTHERSON","AMBUJACEM","SHREECEM","CHOLAFIN",
            "YESBANK","BOSCHLTD","SRF","TORNTPHARM","LUPIN","COLPAL","NAUKRI","DLF","ICICIGI",
            "MPHASIS","LTIM","LTTS","PERSISTENT","TATAPOWER","ACC","BANDHANBNK","INDIGO","ADANIGREEN",
            "PAGEIND","ASHOKLEY","OFSS","GODREJPROP",
            // Nifty Midcap (101-200)
            "BALKRISIND","BATAINDIA","BIOCON","CONCOR","CUMMINSIND","FEDERALBNK","GLENMARK",
            "IDFCFIRSTB","INDUSTOWER","JKCEMENT","JUBLFOOD","LAURUSLABS","MUTHOOTFIN","PIIND","PNB",
            "RBLBANK","SAIL","TATACOMM","TATACHEM","TIINDIA","TORNTPOWER","TVSMOTOR","VBL","VOLTAS",
            "AUROPHARMA","ALKEM","AUBANK","COFORGE","DEEPAKNTR","DIXON","DALBHARAT","HINDPETRO",
            "INDIANB","JSWENERGY","KPITTECH","LALPATHLAB","LINDEINDIA","MANAPPURAM","MARICO","NMDC",
            "NAVINFLUOR","OBEROIRLTY","POLICYBZR","RADICO","RELAXO","SJVN","ZYDUSLIFE","HAPPSTMNDS",
            "MGL","MFSL","METROPOLIS","GUJGASLTD","GRANULES","SYNGENE","SUNTV","RAMCOCEM","PVRINOX",
            "APARINDS","AARTIIND","CROMPTON","ABCAPITAL","APOLLOTYRE","CANFINHOME","ENDURANCE",
            "IEX","ISEC","SKFINDIA","SUMICHEM","TIMKEN","UJJIVANSFB","VGUARD","MAXHEALTH",
            "TATAINVEST","EMAMILTD","ELGIEQUIP","EXIDEIND","FLUOROCHEM","GMRINFRA","ABFRL",
            "ATGL","NYKAA","DELHIVERY","KALYANKJIL","ZEEL","SOLARINDS","KANSAINER","ORIENTELEC",
            "CHAMBLFERT","GNFC","ROUTE","ASTRAL","AFFLE","JYOTHYLAB","IPCALAB","NATCOPHARM",
            "CAMS","SUNDARMFIN","HDFCAMC","IIFL","DEEPAKFERT","HINDCOPPER"
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
        // All NSE F&O eligible stocks (~230): NIFTY_100 + additional liquid F&O names
        STATIC_UNIVERSES.put("FO_STOCKS", List.of(
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
            "TORNTPHARM","TVSMOTOR","VEDL","ZYDUSLIFE","ATGL","TIINDIA","ADANIGREEN",
            // Additional F&O eligible stocks
            "AUBANK","BALKRISIND","BANDHANBNK","BHEL","BIOCON","CANFINHOME","COFORGE","CONCOR",
            "CUMMINSIND","DIXON","ESCORTS","EXIDEIND","FEDERALBNK","GLENMARK","GRANULES",
            "IDFCFIRSTB","IEX","INDIAMART","JKCEMENT","JSWENERGY","JUBLFOOD","LICHSGFIN","LALPATHLAB",
            "MFSL","MPHASIS","NATIONALUM","NBCC","NCC","OBEROIRLTY","PERSISTENT","PETRONET","PNB",
            "PVRINOX","RBLBANK","SAIL","SBICARD","SJVN","SONACOMS","SUNDARMFIN","SUNTV",
            "TATACOMM","TATACHEM","TRIDENT","UBL","VOLTAS","ZEEL",
            "HINDCOPPER","ANGELONE","ASHOKLEY","DEEPAKNTR","MCX","MANAPPURAM","NMDC","PNBHOUSING",
            "HINDPETRO","MRF","ACC","BATAINDIA","CESC","CHAMBLFERT","COROMANDEL",
            "IRFC","KPITTECH","LAURUSLABS","METROPOLIS","MOTILALOFS","ROUTE","SYNGENE",
            "TANLA","THERMAX","TORNTPOWER","VGUARD","LTTS","LTIM","APLAPOLLO","ICICIGI",
            "INDIGO","ALKEM","MINDA","AJANTPHARM","AARTIIND"
        ));
        STATIC_UNIVERSES.put("MSR_WHITELIST", List.of(
            "RELIANCE","DABUR","DMART","IRCTC","SRF","CHOLAFIN","BEL","UPL","BAJAJFINSV",
            "NTPC","TATAMOTORS","NHPC","POLYCAB","TVSMOTOR","HINDUNILVR","ASIANPAINT","WIPRO",
            "TIINDIA","GAIL","ITC","INFY","ZOMATO","INDHOTEL","SHRIRAMFIN","TCS","GRASIM",
            "PAGEIND","POWERGRID","BHARTIARTL","DRREDDY","GODREJCP","CIPLA","HDFCBANK",
            "AUROPHARMA","SUNPHARMA","BAJAJ-AUTO","AXISBANK","HCLTECH","TATAPOWER"
        ));
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (Map.Entry<String, List<String>> entry : STATIC_UNIVERSES.entrySet()) {
                try {
                    syncGroup(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    log.warn("Failed to sync universe group {} (will retry on next restart): {}", entry.getKey(), e.getMessage());
                }
            }
            log.info("Static universe sync completed. {} groups processed.", STATIC_UNIVERSES.size());
        } catch (Exception e) {
            log.error("Static universe sync failed: {}", e.getMessage(), e);
        }
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
